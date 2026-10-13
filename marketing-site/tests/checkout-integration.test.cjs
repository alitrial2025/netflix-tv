const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { createHash } = require('node:crypto');
const fixture = require('./fixtures/lipwa-checkout.json');
const { paymentInput } = require('../server/payment-service.cjs');

test('public Lipwa query handling and real STK serializer preserve the account checkout reference',async()=>{
 const uid='checkout-audit-fixture';const ownerHash=createHash('sha256').update(uid).digest('hex').slice(0,16);
 const reference=`NF_${ownerHash}_basic_${'a'.repeat(32)}`;
 const url=new URL('https://lipwa.link/7976');url.searchParams.set('amount','550');url.searchParams.set('reference',reference);
 // Execute the observed public checkout's actual initialization, without mounting
 // its UI, checking phone numbers, issuing an STK push or contacting any gateway.
 const state={E:url.searchParams,_:{},Ht:value=>value};vm.createContext(state);
 vm.runInContext(fixture.queryInitialization,state);
 assert.equal(state._.amount,550);assert.equal(state._.reference,reference);
 const api=vm.runInNewContext(`({${fixture.stkMethod}})`);
 let request;
 api.backendApi={post:async(path,body)=>{request={path,body};return {data:{success:true}};}};
 await api.mpesaSTKPush(7976,state._.amount,'FIXTURE_PHONE',1,'Fixture',state._.reference);
 assert.equal(request.path,'account/7976/payments');assert.equal(request.body.external_reference,reference);
 const accepted=paymentInput(uid,{planId:'plan_basic',receiptCode:'TEST123456',paymentReference:request.body.external_reference});
 assert.equal(accepted.plan.price,request.body.amount);
});

test('Lipwa clean-URL redirect restores the original account reference from session storage',()=>{
 const reference=`NF_${'a'.repeat(16)}_premium_${'b'.repeat(32)}`;
 let removed=false;
 const state={E:new URLSearchParams(),_:{},Ht:value=>value,sessionStorage:{
  getItem:key=>key==='lipwaParams'?JSON.stringify({amount:'1350',reference}):null,
  removeItem:key=>{assert.equal(key,'lipwaParams');removed=true;}
 }};
 vm.createContext(state);vm.runInContext(fixture.queryInitialization,state);
 assert.equal(state._.reference,null);
 vm.runInContext(fixture.sessionRestore,state);
 assert.equal(state._.reference,reference);assert.equal(state._.amount,1350);assert.equal(removed,true);
});
