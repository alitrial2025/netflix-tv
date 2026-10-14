import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, runTransaction, serverTimestamp, getDoc, getDocs, collection, setDoc, deleteDoc } from 'firebase/firestore';
import { readFile } from 'node:fs/promises';
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
let env;
const day = () => new Date().toISOString().slice(0,10);
before(async () => { assert.equal(await readFile('firestore.rules','utf8'), await readFile('../../firestore.rules','utf8'), 'fixture must match production rules'); env = await initializeTestEnvironment({projectId:'demo-netflixpro-discovery',firestore:{host:'127.0.0.1',port:8787,rules:await readFile('firestore.rules','utf8')}}); });
after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());
const db = uid => env.authenticatedContext(uid).firestore();
async function record(store, uid, key, date=day()) {
 const titleId=key.replace(':','_');
 const vote=doc(store,`users/${uid}/discovery_votes/${date}_${titleId}`);
 const title=doc(store,`community_trends/${date}/titles/${titleId}`);
 const quota=doc(store,`users/${uid}/discovery_days/${date}`);
 return runTransaction(store,async tx => {
  const previous=await tx.get(vote);const total=await tx.get(title);const budget=await tx.get(quota);
  if(!previous.exists() && (budget.data()?.count??0)<20) {
   tx.set(vote,{day:date,key,createdAt:serverTimestamp()});
   tx.set(title,{day:date,key,viewers:(total.data()?.viewers??0)+1,updatedAt:serverTimestamp()});
   tx.set(quota,{count:(budget.data()?.count??0)+1,updatedAt:serverTimestamp()});
  }
 });
}
test('phone and TV concurrent writes count one account once per title/day',async()=> {
 const writes=await Promise.allSettled([record(db('alice'),'alice','tv:73375'),record(db('alice'),'alice','tv:73375')]);
 assert.ok(writes.some(result=>result.status==='fulfilled'));
 assert.equal((await getDoc(doc(db('alice'),`users/alice/discovery_days/${day()}`))).data().count,1);
 assert.equal((await getDoc(doc(db('alice'),`community_trends/${day()}/titles/tv_73375`))).data().viewers,1);
});
test('separate accounts contribute, while same numeric movie/TV ids remain distinct',async()=> {
 await record(db('alice'),'alice','tv:1');await record(db('bob'),'bob','tv:1');await record(db('alice'),'alice','movie:1');
 assert.equal((await getDoc(doc(db('alice'),`community_trends/${day()}/titles/tv_1`))).data().viewers,2);
 assert.equal((await getDoc(doc(db('alice'),`community_trends/${day()}/titles/movie_1`))).data().viewers,1);
});
test('private votes cannot be read or deleted by another account',async()=> {
 await record(db('alice'),'alice','tv:1');
 await assertFails(getDoc(doc(db('bob'),`users/alice/discovery_votes/${day()}_tv_1`)));
 await assertFails(deleteDoc(doc(db('alice'),`users/alice/discovery_votes/${day()}_tv_1`)));
 await assertFails(deleteDoc(doc(db('alice'),`users/alice/discovery_days/${day()}`)));
});
test('forged counters, guests, and past-day votes are rejected',async()=> {
 const ref=doc(db('alice'),`community_trends/${day()}/titles/tv_1`);
 await assertFails(setDoc(ref,{day:day(),key:'tv:1',viewers:500,updatedAt:serverTimestamp()}));
 await assertFails(record(db('alice'),'alice','tv:1','2020-01-01'));
 const guest=env.unauthenticatedContext().firestore();
 await assertFails(getDoc(doc(guest,`community_trends/${day()}/titles/tv_1`)));
});
test('daily account contribution is capped and cannot be incremented without a matching new vote',async()=> {
 for(let i=1;i<=21;i++) await record(db('alice'),'alice',`movie:${i}`);
 const store=db('alice');
 assert.equal((await getDoc(doc(store,`users/alice/discovery_days/${day()}`))).data().count,20);
 assert.equal((await getDoc(doc(store,`community_trends/${day()}/titles/movie_21`))).exists(),false);
 await assertFails(setDoc(doc(store,`community_trends/${day()}/titles/movie_1`),{key:'movie:1',day:day(),viewers:2,updatedAt:serverTimestamp()}));
});

test('profile reminders sync across devices and disabled entries remain tombstones',async()=> {
 const phone=db('alice');const tv=db('alice');
 const path='users/alice/profiles/home/release_reminders/tv_73375';
 await assertSucceeds(setDoc(doc(phone,path),{key:'tv:73375',enabled:true,updatedAt:serverTimestamp()}));
 assert.equal((await getDoc(doc(tv,path))).data().enabled,true);
 await assertSucceeds(setDoc(doc(tv,path),{key:'tv:73375',enabled:false,updatedAt:serverTimestamp()}));
 assert.equal((await getDoc(doc(phone,path))).data().enabled,false);
 await assertFails(getDoc(doc(db('bob'),path)));
 await assertFails(deleteDoc(doc(phone,path)));
 assert.equal((await getDoc(doc(phone,'users/alice/profiles/kids/release_reminders/tv_73375'))).exists(),false);
});
test('anonymous users and malformed reminder identities are rejected',async()=> {
 const guest=env.authenticatedContext('guest',{firebase:{sign_in_provider:'anonymous'}}).firestore();
 await assertFails(getDoc(doc(guest,`community_trends/${day()}/titles/tv_1`)));
 await assertFails(setDoc(doc(db('alice'),'users/alice/profiles/home/release_reminders/tv_1'),{key:'movie:1',enabled:true,updatedAt:serverTimestamp()}));
});

// Force a disable after the migration read, exercising the transaction retry path.
test('legacy reminder migration cannot overwrite a concurrent disable', {timeout:15000}, async()=> {
 const phone=db('alice');const tv=db('alice');
 const path='users/alice/profiles/home/release_reminders/movie_1';
 await runTransaction(phone, async tx => {
  const current=await tx.get(doc(phone,path));
  if(!current.exists()) {
   await setDoc(doc(tv,path),{key:'movie:1',enabled:false,updatedAt:serverTimestamp()});
   tx.set(doc(phone,path),{key:'movie:1',enabled:true,updatedAt:serverTimestamp()});
  }
 });
 assert.equal((await getDoc(doc(phone,path))).data().enabled,false);
});

test('owned app data remains private and removed anonymous TV pairing is denied', async()=> {
 const alice=db('alice');const bob=db('bob');
 for(const path of ['users/alice','users/alice/profiles/home','users/alice/profiles/home/watch_history/tv_1',
  'users/alice/history/movie_1','users/alice/profiles/home/preferences/player']) {
  await assertSucceeds(setDoc(doc(alice,path),{fixture:true}));
  await assertSucceeds(getDoc(doc(alice,path)));
  await assertFails(getDoc(doc(bob,path)));
  await assertFails(setDoc(doc(bob,path),{fixture:false}));
 }
 const publicPairing=env.unauthenticatedContext().firestore();
 await assertFails(setDoc(doc(publicPairing,'tv_sessions/session'),{fixture:true}));
 await assertFails(setDoc(doc(publicPairing,'tv_sessions/session/remote_commands/command'),{fixture:true}));
});

// Membership documents are seeded with administrative privileges, matching the trusted payment service.
const deviceA='a'.repeat(64), deviceB='b'.repeat(64);
async function paidAccount(plan='plan_basic') {
 await env.withSecurityRulesDisabled(async c=>setDoc(doc(c.firestore(),'users/alice/subscription/current'),{planId:plan,status:'ACTIVE',expiresAt:Date.now()+86400000}));
}
const bindingRef=store=>doc(store,'users/alice/device_binding/current');
const bindingData=device=>({deviceId:device,deviceType:'mobile',boundAt:serverTimestamp()});
const slotData=(device,kind='mobile')=>({deviceId:device,deviceType:kind,lastHeartbeat:serverTimestamp(),released:false,leaseToken:'11111111-1111-4111-8111-111111111111'});
test('first-device binding is immutable and private',async()=>{
 await paidAccount();const alice=db('alice');
 await assertSucceeds(setDoc(bindingRef(alice),bindingData(deviceA)));
 await assertFails(setDoc(bindingRef(alice),bindingData(deviceB)));
 await assertFails(deleteDoc(bindingRef(alice)));
 await assertFails(getDoc(bindingRef(db('bob'))));
});
test('only one racing first device can bind a Basic plan',async()=>{
 await paidAccount();const store=db('alice');
 const results=await Promise.allSettled([setDoc(bindingRef(store),bindingData(deviceA)),setDoc(bindingRef(store),bindingData(deviceB))]);
 assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
});
test('Basic playback enforces binding and the single slot',async()=>{
 await paidAccount();const store=db('alice');await setDoc(bindingRef(store),bindingData(deviceA));
 await assertSucceeds(setDoc(doc(store,'users/alice/stream_slots/0'),slotData(deviceA)));
 await assertFails(setDoc(doc(store,'users/alice/stream_slots/0'),slotData(deviceB)));
 await assertFails(setDoc(doc(store,'users/alice/stream_slots/1'),slotData(deviceA)));
});
test('Mobile cannot bind or stream on TV and guests cannot claim slots',async()=>{
 await paidAccount('plan_mobile');const store=db('alice');
 await assertFails(setDoc(bindingRef(store),{...bindingData(deviceA),deviceType:'tv'}));
 await setDoc(bindingRef(store),bindingData(deviceA));
 await assertFails(setDoc(doc(store,'users/alice/stream_slots/0'),slotData(deviceA,'tv')));
 await assertFails(setDoc(doc(db('bob'),'users/bob/stream_slots/0'),slotData(deviceB)));
});
test('Standard cannot steal a live slot or forge heartbeat timestamps',async()=>{
 await paidAccount('plan_standard');const store=db('alice');const ref=doc(store,'users/alice/stream_slots/0');
 await setDoc(ref,slotData(deviceA));
 await assertFails(setDoc(ref,slotData(deviceB)));
 await assertFails(setDoc(doc(store,'users/alice/stream_slots/2'),slotData(deviceA)));
 await assertFails(setDoc(ref,{...slotData(deviceA),lastHeartbeat:new Date(Date.now()+100000)}));
 await setDoc(ref,{...slotData(deviceA),released:true});
 await assertSucceeds(setDoc(ref,slotData(deviceB)));
});
test('expired and suspended memberships cannot claim playback',async()=>{
 const store=db('alice');
 for(const sub of [{planId:'plan_premium',status:'SUSPENDED',expiresAt:Date.now()+86400000},{planId:'plan_premium',status:'ACTIVE',expiresAt:Date.now()-3*86400000}]){
  await env.withSecurityRulesDisabled(async c=>setDoc(doc(c.firestore(),'users/alice/subscription/current'),sub));
  await assertFails(setDoc(doc(store,'users/alice/stream_slots/0'),slotData(deviceA)));
 }
});

// A successful client lookup is not payment evidence: only the trusted service writes entitlements.
const receiptCode = 'TEST123456';
test('missing receipt lookup is private by exact code; guests and enumeration are denied',async()=>{
 await assertSucceeds(getDoc(doc(db('alice'),`used_receipts/${receiptCode}`)));
 await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),`used_receipts/${receiptCode}`)));
 const anon=env.authenticatedContext('guest',{firebase:{sign_in_provider:'anonymous'}}).firestore();
 await assertFails(getDoc(doc(anon,`used_receipts/${receiptCode}`)));
 await assertFails(getDocs(collection(db('alice'),'used_receipts')));
});
test('clients cannot forge receipt approvals or paid membership mirrors',async()=>{
 const store=db('alice');
 await assertFails(setDoc(doc(store,`used_receipts/${receiptCode}`),{receipt:receiptCode,usedByUserId:'alice',planId:'plan_premium'}));
 await assertFails(setDoc(doc(store,'users/alice/subscription/current'),{planId:'plan_premium',status:'ACTIVE',expiresAt:Date.now()+2592000000}));
 await assertFails(setDoc(doc(store,'subscriptions/alice'),{planId:'plan_premium',status:'ACTIVE'}));
 await assertFails(setDoc(doc(store,'users/alice'),{subscriptionPlanId:'plan_premium',subscriptionStatus:'ACTIVE'}));
 await assertSucceeds(setDoc(doc(store,'users/alice'),{email:'test@example.test',subscriptionPlanId:'plan_guest',subscriptionStatus:'NONE'}));
 await assertFails(setDoc(doc(store,'users/alice'),{subscriptionPlanId:'plan_premium'},{merge:true}));
 await assertSucceeds(setDoc(doc(store,'users/alice'),{displayName:'Home'},{merge:true}));
});
test('server-applied membership and immutable receipt are visible only to their owner',async()=>{
 await env.withSecurityRulesDisabled(async c=>{
  await setDoc(doc(c.firestore(),`used_receipts/${receiptCode}`),{receipt:receiptCode,usedByUserId:'alice',planId:'plan_premium'});
  await setDoc(doc(c.firestore(),'users/alice/subscription/current'),{planId:'plan_premium',status:'ACTIVE'});
  await setDoc(doc(c.firestore(),'subscriptions/alice'),{planId:'plan_premium',status:'ACTIVE'});
 });
 for(const path of [`used_receipts/${receiptCode}`,'users/alice/subscription/current','subscriptions/alice']) {
  await assertSucceeds(getDoc(doc(db('alice'),path)));
  await assertFails(getDoc(doc(db('bob'),path)));
  await assertFails(setDoc(doc(db('alice'),path),{planId:'plan_mobile'}));
  await assertFails(deleteDoc(doc(db('alice'),path)));
 }
});
