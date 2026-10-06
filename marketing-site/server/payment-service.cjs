const { createHash } = require('node:crypto');
const PLANS = Object.freeze({
  plan_mobile: { name: 'Mobile', price: 150 }, plan_basic: { name: 'Basic', price: 550 },
  plan_standard: { name: 'Standard', price: 950 }, plan_premium: { name: 'Premium', price: 1350 }
});
class PaymentError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
function text(item, ...names) {
  return names.map(k => item[k] == null ? '' : String(item[k]).trim()).find(v => v && v !== 'null') || '';
}
function hasReceipt(item, code) {
  return text(item, 'provider_reference', 'MpesaReceiptNumber', 'providerReference', 'mpesa_receipt', 'third_party_reference').toUpperCase() === code;
}
function records(json) {
  if (Array.isArray(json)) return json.filter(x => x && typeof x === 'object' && !Array.isArray(x));
  if (!json || typeof json !== 'object') return [];
  const nested = ['response', 'data', 'results', 'transactions'].map(k => json[k]).find(v => v && typeof v === 'object');
  return nested ? records(nested) : [json];
}
function fail(status, code, message) { throw new PaymentError(status, code, message); }
function paymentInput(uid, body) {
  const planId = typeof body?.planId === 'string' ? body.planId : '';
  const plan = Object.hasOwn(PLANS, planId) ? PLANS[planId] : null;
  const receiptCode = String(body?.receiptCode || '').trim().toUpperCase();
  const paymentReference = String(body?.paymentReference || '').trim();
  if (!plan || !/^[A-Z0-9]{8,12}$/.test(receiptCode)) fail(400, 'INVALID_INPUT', 'Select a valid plan and enter the exact M-Pesa confirmation code.');
  const ownerHash = createHash('sha256').update(uid).digest('hex').slice(0, 16);
  const prefix = `NF_${ownerHash}_${body.planId.replace('plan_', '')}_`;
  if (!paymentReference.startsWith(prefix) || !/^[a-f0-9]{32}$/.test(paymentReference.slice(prefix.length)))
    fail(422, 'CHECKOUT_MISMATCH', 'Open checkout from this account and plan, then retry its confirmation code. Do not pay again.');
  return { uid, planId: body.planId, plan, receiptCode, paymentReference };
}
function validateTransaction(payment, input) {
  if (!hasReceipt(payment, input.receiptCode)) fail(422, 'RECEIPT_MISMATCH', 'No exact payment found for this code. Check your confirmation SMS.');
  if (text(payment, 'status', 'Status').toUpperCase() !== 'SUCCESS' || (Object.hasOwn(payment, 'success') && payment.success !== true))
    fail(422, 'PAYMENT_PENDING', 'This payment is not completed. Wait for confirmation and retry the same code; do not pay again.');
  if (text(payment, 'payment_reference', 'external_reference', 'user_reference') !== input.paymentReference)
    fail(422, 'CHECKOUT_MISMATCH', 'This payment does not match checkout for this account and plan. Use its confirmation code; do not pay again.');
  const provider = text(payment, 'provider', 'gateway').toLowerCase();
  if (provider && !['mpesa', 'm-pesa'].includes(provider)) fail(422, 'INVALID_PAYMENT', 'Only incoming M-Pesa membership payments are accepted.');
  validateIncoming(payment);
}
function validateIncoming(payment) {
  const currency = text(payment, 'currency');
  const direction = text(payment, 'transaction_type', 'type').toLowerCase();
  if ((currency && currency.toUpperCase() !== 'KES') || ['withdraw', 'charge', 'payout', 'refund', 'revers'].some(x => direction.includes(x)))
    fail(422, 'INVALID_PAYMENT', 'This receipt is not an incoming KES membership payment.');
}
function amount(payment) {
  const value = Number(payment.amount);
  return Number.isFinite(value) && value > 0 ? value : null;
}
async function lookupPayment(input, gatewayGet) {
  const response = await gatewayGet('transaction-status', { reference: input.receiptCode });
  const matches = records(response).filter(row => hasReceipt(row, input.receiptCode));
  if (matches.length !== 1) fail(422, 'RECEIPT_NOT_FOUND', 'No exact payment found for this code. Check your confirmation SMS and retry; do not pay again.');
  const payment = matches[0];
  validateTransaction(payment, input);
  // Receipt-status lookup alone may not prove that the payment reached this
  // merchant. Always confirm the exact receipt in this authenticated merchant's ledger.
  let merchantPayment = null;
  let page = 1;
  for (let visited = 0; visited < 10; visited++) {
    const ledger = await gatewayGet('transactions', { page: String(page), per: '100' });
    const matchingRows = records(ledger).filter(row => hasReceipt(row, input.receiptCode));
    if (matchingRows.length > 1)
      fail(503, 'PAYMENT_EVIDENCE_MISMATCH', 'The merchant returned conflicting receipt records. Contact support; do not pay again.');
    if (matchingRows.length === 1) { merchantPayment = matchingRows[0]; break; }
    const next = Number(ledger?.pagination?.next_page || 0);
    if (!Number.isInteger(next) || next <= page) break;
    page = next;
  }
  if (!merchantPayment)
    fail(503, 'MERCHANT_RECEIPT_UNCONFIRMED', 'This receipt has not been confirmed in the membership merchant account. Retry shortly or contact support; do not pay again.');
  validateIncoming(merchantPayment);
  const ledgerStatus = text(merchantPayment, 'status', 'Status');
  if ((ledgerStatus && ledgerStatus.toUpperCase() !== 'SUCCESS') ||
      (Object.hasOwn(merchantPayment, 'success') && merchantPayment.success !== true))
    fail(422, 'PAYMENT_PENDING', 'The merchant has not completed this receipt. Retry later; do not pay again.');
  const ledgerProvider = text(merchantPayment, 'provider', 'gateway').toLowerCase();
  if (ledgerProvider && !['mpesa', 'm-pesa'].includes(ledgerProvider))
    fail(422, 'INVALID_PAYMENT', 'Only incoming M-Pesa membership payments are accepted.');
  const ledgerReferences = ['payment_reference', 'external_reference', 'user_reference']
    .map(field => text(merchantPayment, field)).filter(Boolean);
  if (ledgerReferences.some(reference => reference !== input.paymentReference))
    fail(422, 'CHECKOUT_MISMATCH', 'This merchant receipt does not match checkout for this account and plan. Contact support; do not pay again.');
  const paid = amount(merchantPayment);
  if (paid == null)
    fail(503, 'AMOUNT_UNCONFIRMED', 'The merchant has not confirmed this receipt amount. Retry shortly or contact support; do not pay again.');
  if (!Number.isSafeInteger(paid) || paid < input.plan.price || paid > 2147483647)
    fail(422, 'INSUFFICIENT_AMOUNT', `The confirmed payment does not cover this plan (KES ${input.plan.price}).`);
  if (payment.amount != null && String(payment.amount).trim() !== '' && Number(payment.amount) !== paid)
    fail(503, 'PAYMENT_EVIDENCE_MISMATCH', 'The merchant receipt amount conflicts with its payment status. Contact support; do not pay again.');
  return { amountPaid: paid, gatewayReference: text(payment, 'reference') };
}
function existingPayment(receipt, subscription, input) {
  if (receipt.usedByUserId !== input.uid || receipt.planId !== input.planId)
    fail(409, 'RECEIPT_USED', 'This code was already redeemed for another account or plan. Each payment can activate only one membership.');
  if (!subscription || subscription.mpesaReceipt !== input.receiptCode || subscription.planId !== input.planId)
    fail(409, 'RECEIPT_USED', 'This payment has already been redeemed. It cannot add another membership period.');
  return { subscription, amountPaid: Number(receipt.amount) || subscription.amount, message: 'This payment is already applied to your account.' };
}
async function activate(input, payment, { db, serverTimestamp, now = Date.now }) {
  const receiptRef = db.collection('used_receipts').doc(input.receiptCode);
  const userRef = db.collection('users').doc(input.uid);
  const currentRef = userRef.collection('subscription').doc('current');
  return db.runTransaction(async tx => {
    const redeemed = await tx.get(receiptRef);
    const current = await tx.get(currentRef);
    if (redeemed.exists) return existingPayment(redeemed.data(), current.exists ? current.data() : null, input);
    const previous = current.exists ? current.data() : {};
    const activatedAt = now();
    const previousExpiry = previous.planId === input.planId && previous.status === 'ACTIVE' && Number.isSafeInteger(previous.expiresAt) && previous.expiresAt > activatedAt ? previous.expiresAt : activatedAt;
    const subscription = { status: 'ACTIVE', planId: input.planId, planName: input.plan.name,
      amount: input.plan.price, currency: 'KES', paymentReference: input.paymentReference,
      mpesaReceipt: input.receiptCode, subscribedAt: activatedAt, expiresAt: previousExpiry + 30 * 86400000 };
    const data = { ...subscription, updatedAt: serverTimestamp() };
    tx.create(receiptRef, { receipt: input.receiptCode, usedByUserId: input.uid, planId: input.planId,
      amount: payment.amountPaid, currency: 'KES', paymentReference: input.paymentReference,
      gatewayReference: payment.gatewayReference, expiresAt: subscription.expiresAt, verifiedAt: serverTimestamp() });
    tx.set(currentRef, data, { merge: true });
    tx.set(db.collection('subscriptions').doc(input.uid), { ...data, userId: input.uid }, { merge: true });
    tx.set(userRef, { subscriptionPlanId: input.planId, subscriptionStatus: 'ACTIVE', updatedAt: serverTimestamp() }, { merge: true });
    return { subscription, amountPaid: payment.amountPaid, message: `Payment verified. Your ${input.plan.name} membership is active for 30 days.` };
  });
}
function createGatewayGet(authorization, fetchImpl = fetch, deadline = Date.now() + 45000) {
  return async (path, parameters) => {
    if (!authorization) fail(503, 'NOT_CONFIGURED', 'Payment verification is temporarily unavailable. Contact support; do not pay again.');
    const remaining = deadline - Date.now();
    if (remaining <= 0) fail(503, 'GATEWAY_UNAVAILABLE', 'Verification took too long. Retry this same code shortly; do not pay again.');
    const url = new URL(`https://backend.payhero.co.ke/api/v2/${path}`);
    Object.entries(parameters).forEach(([k, v]) => url.searchParams.set(k, v));
    let response;
    try { response = await fetchImpl(url, { headers: { Accept: 'application/json', Authorization: authorization }, redirect: 'error', signal: AbortSignal.timeout(Math.min(20000, remaining)) }); }
    catch (_) { fail(503, 'GATEWAY_UNAVAILABLE', 'Payment verification is temporarily unavailable. Retry this same code; do not pay again.'); }
    if (!response.ok) fail(503, 'GATEWAY_UNAVAILABLE', 'The merchant lookup is unavailable. Retry shortly or contact support with your receipt; do not pay again.');
    let json;
    try { json = await response.json(); }
    catch (_) { fail(503, 'GATEWAY_UNAVAILABLE', 'The merchant returned an invalid response. Retry the same code shortly; do not pay again.'); }
    return json;
  };
}
function createHandler({ verifyIdToken, db, serverTimestamp, gatewayGet, now }) {
  return async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const send = (status, body) => res.status(status).json(body);
    if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return send(405, { code: 'METHOD_NOT_ALLOWED' }); }
    try {
      const token = /^Bearer ([^\s]+)$/.exec(req.headers.authorization || '')?.[1];
      if (!token) fail(401, 'AUTH_REQUIRED', 'Sign in to your account before verifying payment.');
      let account;
      try { account = await verifyIdToken(token); }
      catch (_) { fail(401, 'AUTH_REQUIRED', 'Your sign-in could not be confirmed. Sign in to the same account and retry this code; do not pay again.'); }
      if (!account.uid || account.firebase?.sign_in_provider === 'anonymous') fail(401, 'AUTH_REQUIRED', 'Sign in to your account before verifying payment.');
      let body = req.body;
      if (typeof body === 'string') { try { body = JSON.parse(body); } catch (_) { fail(400, 'INVALID_INPUT', 'Enter a valid membership confirmation code.'); } }
      const input = paymentInput(account.uid, body);
      const receiptRef = db.collection('used_receipts').doc(input.receiptCode);
      const existing = await receiptRef.get();
      if (existing.exists) {
        const current = await db.collection('users').doc(input.uid).collection('subscription').doc('current').get();
        return send(200, existingPayment(existing.data(), current.exists ? current.data() : null, input));
      }
      const payment = await lookupPayment(input, gatewayGet);
      return send(200, await activate(input, payment, { db, serverTimestamp, now }));
    } catch (error) {
      if (error instanceof PaymentError) return send(error.status, { code: error.code, message: error.message });
      // Never log bearer tokens, merchant credentials, customer receipts or personal data.
      console.warn('Payment activation failed', { category: 'MEMBERSHIP_UNAVAILABLE' });
      return send(503, { code: 'MEMBERSHIP_UNAVAILABLE', message: 'Payment could not be saved. Retry this same code after reconnecting or contact support; do not pay again.' });
    }
  };
}
module.exports = { PLANS, PaymentError, paymentInput, validateTransaction, lookupPayment, existingPayment, activate, createHandler, createGatewayGet };
