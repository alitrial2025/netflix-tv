const test = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { paymentInput, lookupPayment, activate, createHandler, existingPayment } = require('../server/payment-service.cjs');
const reference = (uid='alice',plan='plan_premium') => `NF_${createHash('sha256').update(uid).digest('hex').slice(0,16)}_${plan.replace('plan_','')}_${'b'.repeat(32)}`;
const input = (uid='alice',plan='plan_premium') => paymentInput(uid,{planId:plan,receiptCode:'TEST123456',paymentReference:reference(uid,plan)});
const payment = () => ({provider_reference:'TEST123456',status:'SUCCESS',provider:'M-PESA',currency:'KES',external_reference:reference(),transaction_type:'PAYMENT',amount:1350,reference:'merchant-transaction'});
const merchantGateway = (status = payment(), ledger = status) => async path => path === 'transaction-status' ? {data:status} : {data:[ledger]};
function mockDb() {
 const data = new Map(); let queue=Promise.resolve();
 const snapshot = path => ({exists:data.has(path),data:()=>data.get(path)});
 const doc = path => ({path,get:async()=>snapshot(path),collection:name=>({doc:key=>doc(`${path}/${name}/${key}`)})});
 const db = {data,collection:name=>({doc:key=>doc(`${name}/${key}`)}),runTransaction:fn=>{
  const result=queue.then(async()=>{
   const pending=[];
   const tx={get:async ref=>snapshot(ref.path),create:(ref,value)=>{assert.equal(data.has(ref.path),false);pending.push([ref.path,value]);},set:(ref,value)=>pending.push([ref.path,{...data.get(ref.path),...value}])};
   const value=await fn(tx);pending.forEach(([path,obj])=>data.set(path,obj));return value;
  });queue=result.catch(()=>{});return result;
 }};
 return db;
}
const deps = db => ({db,serverTimestamp:()=>123456789,now:()=>1700000000000});
async function invoke(handler,body,account='Bearer valid') {
 let status;let result;const headers={};
 const res={setHeader:(k,v)=>headers[k]=v,status:n=>{status=n;return res;},json:value=>{result=value;return res;}};
 await handler({method:'POST',headers:{authorization:account},body},res);return {status,result,headers};
}
test('only merchant-confirmed exact successful incoming KES receipt activates the server-selected price',async()=>{
 const approved=await lookupPayment(input(),merchantGateway());assert.equal(approved.amountPaid,1350);
 for(const [key,value] of [['provider_reference','COPIED1234'],['external_reference',reference('bob')],['status','PENDING'],['success',false],['currency','USD'],['transaction_type','WITHDRAWAL'],['transaction_type','REFUND'],['transaction_type','REVERSAL'],['amount',1349],['amount',1350.5]]) {
  await assert.rejects(lookupPayment(input(),merchantGateway({...payment(),[key]:value})),undefined,`Reject ${key}=${value}`);
 }
});
test('missing account checkout reference never accepts an unrelated transaction',async()=>{
 const paid=payment();delete paid.external_reference;
 await assert.rejects(lookupPayment(input(),async()=>paid),{code:'CHECKOUT_MISMATCH'});
 assert.throws(()=>paymentInput('alice',{planId:'plan_premium',receiptCode:'TEST123456',paymentReference:reference('bob')}),{code:'CHECKOUT_MISMATCH'});
 assert.throws(()=>paymentInput('alice',{planId:'plan_basic',receiptCode:'TEST123456',paymentReference:reference('alice','plan_premium')}),{code:'CHECKOUT_MISMATCH'});
});
test('a status without amount must match the same incoming receipt in the authenticated merchant ledger',async()=>{
 const status=payment();delete status.amount;
 let calls=0;const approved=await lookupPayment(input(),async path=>{calls++;return path==='transaction-status'?status:{data:[payment()]};});
 assert.equal(approved.amountPaid,1350);assert.equal(calls,2);
 await assert.rejects(lookupPayment(input(),async path=>path==='transaction-status'?status:{data:[{...payment(),provider_reference:'DIFFERENT9'}]}),{code:'MERCHANT_RECEIPT_UNCONFIRMED'});
 await assert.rejects(lookupPayment(input(),async path=>path==='transaction-status'?status:{data:[{...payment(),transaction_type:'REFUND'}]}),{code:'INVALID_PAYMENT'});
});
test('membership writes occur together only after gateway evidence passes; client-provided prices are ignored',async()=>{
 const db=mockDb();const handler=createHandler({...deps(db),verifyIdToken:async()=>({uid:'alice',firebase:{sign_in_provider:'password'}}),gatewayGet:merchantGateway()});
 const result=await invoke(handler,{planId:'plan_premium',receiptCode:'TEST123456',paymentReference:reference(),planPrice:1});
 assert.equal(result.status,200);assert.equal(result.result.subscription.amount,1350);assert.equal(db.data.size,4);
 assert.equal(db.data.get('users/alice').subscriptionPlanId,'plan_premium');
 assert.equal(db.data.get('used_receipts/TEST123456').usedByUserId,'alice');
 assert.equal(db.data.get('subscriptions/alice').expiresAt,result.result.subscription.expiresAt);
});
test('failed or fake gateway verification leaves receipt and membership entirely untouched',async()=>{
 const db=mockDb();const handler=createHandler({...deps(db),verifyIdToken:async()=>({uid:'alice'}),gatewayGet:async()=>({...payment(),provider_reference:'FAKE123456'})});
 const response=await invoke(handler,{planId:'plan_premium',receiptCode:'TEST123456',paymentReference:reference()});
 assert.equal(response.status,422);assert.equal(db.data.size,0);
});
test('concurrent retries create one receipt and add exactly one membership period',async()=>{
 const db=mockDb();const [first,second]=await Promise.all([activate(input(),{amountPaid:1350,gatewayReference:'merchant'},deps(db)),activate(input(),{amountPaid:1350,gatewayReference:'merchant'},deps(db))]);
 assert.equal(first.subscription.expiresAt,second.subscription.expiresAt);
 assert.equal(first.subscription.expiresAt-first.subscription.subscribedAt,2592000000);assert.equal(db.data.size,4);
 const receipt=db.data.get('used_receipts/TEST123456');const sub=db.data.get('users/alice/subscription/current');
 assert.throws(()=>existingPayment(receipt,sub,input('bob')),{code:'RECEIPT_USED'});
 assert.throws(()=>existingPayment(receipt,sub,input('alice','plan_basic')),{code:'RECEIPT_USED'});
});
test('active same-plan renewal extends remaining days once; a redeemed older receipt never extends them again',async()=>{
 const db=mockDb();db.data.set('users/alice/subscription/current',{planId:'plan_premium',status:'ACTIVE',expiresAt:1700001000000});
 const first=await activate(input(),{amountPaid:1350,gatewayReference:'merchant'},deps(db));
 assert.equal(first.subscription.expiresAt,1700001000000+2592000000);
 const second=await activate(input(),{amountPaid:1350,gatewayReference:'merchant'},deps(db));assert.equal(first.subscription.expiresAt,second.subscription.expiresAt);
 const sub={...first.subscription,mpesaReceipt:'NEWER12345'};
 assert.throws(()=>existingPayment(db.data.get('used_receipts/TEST123456'),sub,input()),{code:'RECEIPT_USED'});
});
test('anonymous, expired, missing, or another-account authentication cannot reach merchant lookup',async()=>{
 for(const verifyIdToken of [async()=>({uid:'guest',firebase:{sign_in_provider:'anonymous'}}),async()=>{throw Error('expired');}]) {
  const db=mockDb();let gatewayCalls=0;
  const handler=createHandler({...deps(db),verifyIdToken,gatewayGet:async()=>{gatewayCalls++;return payment();}});
  assert.equal((await invoke(handler,{planId:'plan_premium',receiptCode:'TEST123456',paymentReference:reference()})).status,401);
  assert.equal(gatewayCalls,0);assert.equal(db.data.size,0);
 }
});
test('atomic activation failure never returns successful membership',async()=>{
 const db=mockDb();db.runTransaction=async()=>{throw Error('unavailable');};
 const handler=createHandler({...deps(db),verifyIdToken:async()=>({uid:'alice'}),gatewayGet:merchantGateway()});
 const response=await invoke(handler,{planId:'plan_premium',receiptCode:'TEST123456',paymentReference:reference()});
 assert.equal(response.status,503);assert.equal(response.result.code,'MEMBERSHIP_UNAVAILABLE');assert.equal(db.data.size,0);
});

test('another merchant receipt cannot grant membership even when status has exact successful amount and checkout',async()=>{
 const db=mockDb();const calls=[];
 const gatewayGet=async path=>{calls.push(path);return path==='transaction-status'?payment():{data:[{...payment(),provider_reference:'OTHER12345'}]};};
 const handler=createHandler({...deps(db),verifyIdToken:async()=>({uid:'alice'}),gatewayGet});
 const response=await invoke(handler,{planId:'plan_premium',receiptCode:'TEST123456',paymentReference:reference()});
 assert.equal(response.status,503);assert.equal(response.result.code,'MERCHANT_RECEIPT_UNCONFIRMED');
 assert.deepEqual(calls,['transaction-status','transactions']);assert.equal(db.data.size,0);
});
test('merchant ledger amount is mandatory and conflicts or mismatched checkout cannot approve a receipt',async()=>{
 await assert.rejects(lookupPayment(input(),merchantGateway(payment(),{...payment(),amount:150})),{code:'INSUFFICIENT_AMOUNT'});
 await assert.rejects(lookupPayment(input(),merchantGateway({...payment(),amount:1500},payment())),{code:'PAYMENT_EVIDENCE_MISMATCH'});
 await assert.rejects(lookupPayment(input(),merchantGateway(payment(),{...payment(),external_reference:reference('bob')})),{code:'CHECKOUT_MISMATCH'});
 await assert.rejects(lookupPayment(input(),merchantGateway(payment(),{...payment(),payment_reference:reference(),external_reference:reference('bob')})),{code:'CHECKOUT_MISMATCH'});
 const ledger=payment();delete ledger.amount;
 await assert.rejects(lookupPayment(input(),merchantGateway(payment(),ledger)),{code:'AMOUNT_UNCONFIRMED'});
 await assert.rejects(lookupPayment(input(),merchantGateway(payment(),{...payment(),status:'PENDING'})),{code:'PAYMENT_PENDING'});
 await assert.rejects(lookupPayment(input(),merchantGateway(payment(),{...payment(),transaction_type:'REFUND'})),{code:'INVALID_PAYMENT'});
 const legacyLedger=payment();delete legacyLedger.external_reference;delete legacyLedger.status;
 assert.equal((await lookupPayment(input(),merchantGateway(payment(),legacyLedger))).amountPaid,1350);
});
test('merchant ledger search is paginated and strictly bounded when an exact receipt is absent',async()=>{
 const pages=[];
 const gateway=async(path,params)=>{
  if(path==='transaction-status') return payment();
  pages.push(Number(params.page));
  return Number(params.page)===2?{data:[payment()]}:{data:[],pagination:{next_page:2}};
 };
 assert.equal((await lookupPayment(input(),gateway)).amountPaid,1350);assert.deepEqual(pages,[1,2]);
 let requests=0;
 await assert.rejects(lookupPayment(input(),async(path,params)=>{
  if(path==='transaction-status') return payment();requests++;return {data:[],pagination:{next_page:Number(params.page)+1}};
 }),{code:'MERCHANT_RECEIPT_UNCONFIRMED'});
 assert.equal(requests,10);
});
