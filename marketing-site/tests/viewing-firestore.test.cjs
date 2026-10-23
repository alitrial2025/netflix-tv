const test=require('node:test'),assert=require('node:assert/strict');
const {createHash,randomUUID}=require('node:crypto');
const {viewingAction}=require('../server/viewing-service.cjs');
const enabled=Boolean(process.env.FIRESTORE_EMULATOR_HOST);
let app,db;
const now=1800000000000;
const target={kind:'tv',id:'4604',title:'Smallville',season:6,episode:22};
const playback={positionMs:12000,durationMs:2400000,playing:true,buffering:false};
async function fixture() {
 if (!db) {
  const {initializeApp}=require('firebase-admin/app');
  app=initializeApp({projectId:'demo-netflixpro-discovery'},'viewing-emulator');
  db=require('firebase-admin/firestore').getFirestore(app);
 }
 const host='host-'+randomUUID(),guest='guest-'+randomUUID();
 for (const uid of [host,guest]) await db.doc(`users/${uid}/subscription/current`).set({planId:'plan_basic',status:'ACTIVE',expiresAt:now+86400000});
 let time=now;
 const deps={db,now:()=>time,codeGenerator:()=>randomUUID().replaceAll('-','').slice(0,10).toUpperCase()};
 const room=await viewingAction(host,{action:'create',deviceId:'phone',target,playback},deps);
 return {host,guest,room,deps,advance:ms=>{time+=ms;}};
}
test.after(async()=>{if(db)await db.terminate();if(app)await require('firebase-admin/app').deleteApp(app);});

test('Firestore SDK commits rejected admissions while rolling back staged detach writes',{skip:!enabled},async()=>{
 const f=await fixture();const {host,guest,room,deps}=f;
 await viewingAction(guest,{action:'join',deviceId:'phone',roomCode:room.roomCode},deps);
 const ref=db.doc('viewing_rooms/'+room.roomCode),mirror=db.doc(`users/${guest}/viewing_sessions/phone`);
 const before=(await ref.get()).data(),beforeMirror=(await mirror.get()).data();
 const rate=db.doc('viewing_rates/'+createHash('sha256').update(host).digest('hex'));
 const count=(await rate.get()).data().times.length;
 f.advance(90001);
 await assert.rejects(viewingAction(host,{action:'create',deviceId:'phone',target:{...target,id:'invalid'},playback},deps),{code:'INVALID_TITLE'});
 assert.deepEqual((await ref.get()).data(),before);
 assert.deepEqual((await mirror.get()).data(),beforeMirror);
 assert.equal((await rate.get()).data().times.length,1); // Old rate entries expired; rejection still committed.
 assert.ok(count>0);
});

test('Firestore retries concurrent controls atomically and rechecks membership on every request',{skip:!enabled},async()=>{
 const {host,room,deps}=await fixture();
 const control=playing=>viewingAction(host,{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing}},deps);
 const results=await Promise.allSettled([control(false),control(true)]);
 assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
 assert.equal(results.find(r=>r.status==='rejected').reason.code,'STALE_CONTROL');
 const rate=await db.doc('viewing_rates/'+createHash('sha256').update(host).digest('hex')).get();
 assert.equal(rate.data().times.length,3); // Create plus both attempted controls.
 assert.equal((await db.doc('viewing_rooms/'+room.roomCode).get()).data().revision,room.revision+1);
 await db.doc(`users/${host}/subscription/current`).update({status:'SUSPENDED'});
 await assert.rejects(viewingAction(host,{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},deps),{code:'MEMBERSHIP_REQUIRED'});
});

test('Firestore renews host presence without moving the playback timeline',{skip:!enabled},async()=>{
 const f=await fixture();const {host,guest,room,deps}=f;
 await viewingAction(guest,{action:'join',deviceId:'phone',roomCode:room.roomCode},deps);
 const mirror=db.doc(`users/${guest}/viewing_sessions/phone`),before=await mirror.get();
 f.advance(11999);
 const quiet=await viewingAction(host,{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},deps);
 const unchanged=await mirror.get();assert.ok(!unchanged.updateTime.isEqual(before.updateTime));
 assert.equal(quiet.eventRevision,before.data().eventRevision+1);
 f.advance(20000);
 const fresh=await viewingAction(host,{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},deps);
 const changed=await mirror.get();assert.ok(!changed.updateTime.isEqual(before.updateTime));
 assert.equal(fresh.hostSeenAtMs,now+31999);assert.equal(changed.data().hostSeenAtMs,fresh.hostSeenAtMs);
 assert.equal(changed.data().updatedAtMs,before.data().updatedAtMs);
});

test('Firestore preserves same-account Premium host and TV claims under concurrent party traffic',{skip:!enabled},async()=>{
 const f=await fixture();const {host,deps}=f;
 await db.doc(`users/${host}/subscription/current`).update({planId:'plan_premium'});
 let room=f.room;
 f.advance(2000);
 await viewingAction(host,{action:'join',deviceId:'tv',deviceKind:'tv',roomCode:room.roomCode,name:'TV'},deps);
 for(let round=0;round<2;round++){
  f.advance(20000);
  const responses=await Promise.all([
   viewingAction(host,{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},deps),
   viewingAction(host,{action:'heartbeat',deviceId:'tv',deviceKind:'tv',roomCode:room.roomCode},deps),
   viewingAction(host,{action:'register',deviceId:'tv',deviceKind:'tv',name:'TV'},deps),
   viewingAction(host,{action:'comment',deviceId:'tv',deviceKind:'tv',roomCode:room.roomCode,messageId:'m'+round,text:'Hi'},deps),
   viewingAction(host,{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:round%2===0}},deps)
  ]);
  room=responses[4];
  const account=(await db.doc('viewing_accounts/'+createHash('sha256').update(host).digest('hex')).get()).data();
  assert.equal(account.rooms.phone.code,room.roomCode);assert.equal(account.rooms.tv.code,room.roomCode);
  const tv=(await db.doc(`users/${host}/viewing_sessions/tv`).get()).data();
  assert.equal(tv.ended,false);assert.equal(tv.isHost,false);assert.equal(tv.members.length,2);
  assert.equal(tv.messages.length,round+1);assert.equal(tv.playback.playing,round%2===0);
 }
});
