const { initializeApp, cert, getApps } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { createHandler, createGatewayGet } = require('../../server/payment-service.cjs');

module.exports = async function verifyPayment(req, res) {
  try {
    if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON || !process.env.PAYHERO_API_AUTH) throw new Error('Payment service is not configured');
    const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
    if (serviceAccount.project_id !== 'netflixpro-cca67') throw new Error('Unexpected payment project');
    const app = getApps().find(a => a.name === 'payments') || initializeApp({ credential: cert(serviceAccount), projectId: serviceAccount.project_id }, 'payments');
    return await createHandler({
      verifyIdToken: token => getAuth(app).verifyIdToken(token, true),
      db: getFirestore(app), serverTimestamp: () => FieldValue.serverTimestamp(),
      gatewayGet: createGatewayGet(process.env.PAYHERO_API_AUTH)
    })(req, res);
  } catch (_) {
    res.setHeader('Cache-Control', 'no-store');
    return res.status(503).json({ code: 'NOT_CONFIGURED', message: 'Payment verification is temporarily unavailable. Contact support with your receipt; do not pay again.' });
  }
};
