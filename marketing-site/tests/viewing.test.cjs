const test=require('node:test'),assert=require('node:assert/strict');
const {viewingAction,createViewingHandler,targetInput,playbackInput,allowed}=require('../server/viewing-service.cjs');
const now=1800000000000,target={kind:'tv',id:'4604',title:'Smallville',season:6,episode:22},playback={positionMs:12000,durationMs:2400000,playing:true,buffering:false};
function dbMock(){
 const data=new Map(),usage={transactions:0,reads:[],writes:[]};let queue=Promise.resolve();
 const doc=path=>({path,collection:name=>collection(path+'/'+name)}),collection=path=>({doc:id=>doc(path+'/'+id)});
 function merge(a,b){const v={...a};for(const[k,x]of Object.entries(b))v[k]=x&&typeof x==='object'&&!Array.isArray(x)?merge(a?.[k]||{},x):x;return v;}
 return {data,usage,collection,runTransaction:fn=>{const result=queue.then(async()=>{usage.transactions++;const writes=[];const tx={get:async r=>{usage.reads.push(r.path);assert.equal(writes.length,0);return {exists:data.has(r.path),data:()=>structuredClone(data.get(r.path))};},set:(r,v,options)=>writes.push([r.path,structuredClone(v),options])};const result=await fn(tx);for(const[p,v,o]of writes){data.set(p,o?.merge?merge(data.get(p),v):v);usage.writes.push(p);}return result;});queue=result.catch(()=>{});return result;}};
}
function deps(db,clock=()=>now){let next=0;return{db,now:clock,codeGenerator:()=>String(++next).padStart(10,'A'),uuid:()=>`member-${++next}`};}
const member=(db,id,plan='plan_premium')=>db.data.set(`users/${id}/subscription/current`,{planId:plan,status:'ACTIVE',expiresAt:now+86400000});
const create=(d,uid='host',deviceId='phone')=>viewingAction(uid,{action:'create',deviceId,target,playback,name:'Host'},d);
test('untrusted targets and positions reject URLs, oversized IDs, NaN and invalid episode coordinates',()=>{
 for(const input of [{...target,id:'https://evil.test'},{...target,kind:'stream'},{...target,episode:0},{...target,title:''}])assert.throws(()=>targetInput(input));
 for(const input of [{...playback,positionMs:NaN},{...playback,positionMs:2400001},{...playback,playing:'yes'}])assert.throws(()=>playbackInput(input));
});
test('paid membership and TV access follow expiry, grace, suspension and known tiers',()=>{
 for(const plan of ['plan_guest','plan_admin'])assert.equal(allowed({planId:plan,status:'ACTIVE',expiresAt:now+1},now,'mobile'),false);
 assert.equal(allowed({planId:'plan_mobile',status:'ACTIVE',expiresAt:now+1},now,'tv'),false);
 assert.equal(allowed({planId:'plan_premium',status:'SUSPENDED',expiresAt:now+1},now,'tv'),false);
 assert.equal(allowed({planId:'plan_basic',status:'GRACE_PERIOD',expiresAt:now-172800000+1},now,'tv'),true);
 assert.equal(allowed({planId:'plan_basic',status:'ACTIVE',expiresAt:now-172800000},now,'tv'),false);
});
test('private rooms expose only member names and IDs to account-owned mirrors; only host can control',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');const d=deps(db);const room=await create(d);
 const joined=await viewingAction('guest',{action:'join',deviceId:'tv',deviceKind:'tv',roomCode:room.roomCode,name:'Guest'},d);
 assert.equal(joined.isHost,false);assert.equal(joined.members.length,2);assert.ok(!JSON.stringify(joined).includes('hostUid'));
 await assert.rejects(viewingAction('guest',{action:'control',deviceId:'tv',roomCode:room.roomCode,expectedRevision:joined.revision,playback},d),{code:'HOST_ONLY'});
 const changed=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:joined.revision,playback:{...playback,playing:false}},d);
 assert.equal(changed.playback.playing,false);assert.equal(db.data.get('users/guest/viewing_sessions/tv').playback.playing,false);
 await assert.rejects(viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:joined.revision,playback},d),{code:'STALE_CONTROL'});
});
test('room limits, duplicate joins and new-title position reset are atomic',async()=>{
 const db=dbMock();const d=deps(db);member(db,'host');const room=await create(d);
 for(let i=0;i<7;i++){member(db,'g'+i);await viewingAction('g'+i,{action:'join',deviceId:'phone',roomCode:room.roomCode},d);}
 member(db,'extra');await assert.rejects(viewingAction('extra',{action:'join',deviceId:'phone',roomCode:room.roomCode},d),{code:'PARTY_FULL'});
 const current=await viewingAction('host',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);assert.equal(current.members.length,8);
 await assert.rejects(viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:current.revision,target:{...target,episode:23},playback},d),{code:'INVALID_PLAYBACK'});
});
test('ending a party clears guests membership so they can create another, and ended codes cannot rejoin',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');const d=deps(db);const room=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);
 await viewingAction('host',{action:'end',deviceId:'phone',roomCode:room.roomCode},d);
 assert.equal(db.data.get('users/guest/viewing_sessions/phone').ended,true);
 await assert.rejects(viewingAction('guest',{action:'join',deviceId:'other',roomCode:room.roomCode},d),{code:'PARTY_ENDED'});
 assert.ok((await create(d,'guest')).isHost);
});
test('handoff is same-account, short-lived, and only addresses a recently registered TV',async()=>{
 const db=dbMock();member(db,'a');member(db,'b');const d=deps(db);
 await viewingAction('a',{action:'register',deviceId:'tv',deviceKind:'tv',name:'Living room'},d);
 assert.deepEqual((await viewingAction('a',{action:'devices',deviceId:'phone'},d)).devices,[{id:'tv',name:'Living room'}]);
 await assert.rejects(viewingAction('b',{action:'handoff',deviceId:'phone',targetDeviceId:'tv',target,positionMs:12000},d),{code:'TV_UNAVAILABLE'});
 await viewingAction('a',{action:'handoff',deviceId:'phone',targetDeviceId:'tv',target,positionMs:12000},d);
 const sent=db.data.get('users/a/viewing_commands/tv');assert.equal(sent.positionMs,12000);assert.equal(sent.expiresAtMs-now,30000);assert.equal(sent.target.id,'4604');
});
test('rate limits do not alter an already committed room and expired rooms reject controls',async()=>{
 const db=dbMock();member(db,'host');let time=now;const d=deps(db,()=>time);const room=await create(d);
 for(let i=0;i<7;i++)await viewingAction('host',{action:'devices',deviceId:'phone'},d);
 await assert.rejects(viewingAction('host',{action:'devices',deviceId:'phone'},d),{code:'SLOW_DOWN'});
 time+=21600000;await assert.rejects(viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d),{code:'PARTY_ENDED'});
});
test('endpoint requires verified non-anonymous identity, ignores body UID and never leaks internal errors',async()=>{
 const db=dbMock();member(db,'alice');let status,payload;const res={setHeader(){},status(n){status=n;return this;},json(x){payload=x;}};
 const handler=createViewingHandler({...deps(db),verifyIdToken:async()=>({uid:'alice',firebase:{sign_in_provider:'password'}})});
 await handler({method:'POST',headers:{},body:{}},res);assert.equal(status,401);
 await handler({method:'POST',headers:{authorization:'Bearer ok'},body:{action:'register',deviceId:'phone',uid:'bob'}},res);assert.equal(status,200);
 assert.ok([...db.data.keys()].every(p=>!p.includes('/bob/')));
 const anonymous=createViewingHandler({...deps(db),verifyIdToken:async()=>({uid:'alice',firebase:{sign_in_provider:'anonymous'}})});
 await anonymous({method:'POST',headers:{authorization:'Bearer ok'},body:{}},res);assert.equal(status,401);assert.ok(!JSON.stringify(payload).includes('firebase'));
});
test('device registration preserves an active room claim and profile-change double-create cannot bypass it',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 await viewingAction('host',{action:'register',deviceId:'phone',name:'Different profile'},d);
 await assert.rejects(create(d),{code:'ALREADY_IN_PARTY'});
 const paused=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:false}},d);
 assert.equal(paused.playback.playing,false);
});
test('rooms are isolated by authenticated account and device even when device names match',async()=>{
 const db=dbMock();member(db,'same');member(db,'other');const d=deps(db);
 const one=await create(d,'same','mobile');const two=await create(d,'same','tv');
 assert.notEqual(one.roomCode,two.roomCode);
 await assert.rejects(viewingAction('same',{action:'control',deviceId:'tv',roomCode:one.roomCode,expectedRevision:one.revision,playback},d),{code:'NOT_A_MEMBER'});
 await assert.rejects(viewingAction('other',{action:'heartbeat',deviceId:'mobile',roomCode:one.roomCode},d),{code:'NOT_A_MEMBER'});
 assert.equal(db.data.get('users/same/viewing_sessions/mobile').roomCode,one.roomCode);
 assert.equal(db.data.get('users/same/viewing_sessions/tv').roomCode,two.roomCode);
});
test('departing an expired guest lease cannot let the old host erase the guest’s newer party',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const old=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:old.roomCode},d);
 time+=90001;const next=await create(d,'guest');
 await viewingAction('host',{action:'end',deviceId:'phone',roomCode:old.roomCode},d);
 assert.equal(db.data.get('users/guest/viewing_sessions/phone').roomCode,next.roomCode);
 await assert.rejects(create(d,'guest'),{code:'ALREADY_IN_PARTY'});
 const updated=await viewingAction('guest',{action:'control',deviceId:'phone',roomCode:next.roomCode,expectedRevision:next.revision,playback:{...playback,playing:false}},d);
 assert.equal(updated.playback.playing,false);
});
test('moving an expired host lease ends the old room instead of controlling two rooms from one device',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const old=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:old.roomCode},d);
 time+=90001;const next=await create(d);
 assert.equal(db.data.get('users/guest/viewing_sessions/phone').ended,true);
 await assert.rejects(viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:old.roomCode},d),{code:'PARTY_ENDED'});
 assert.equal(db.data.get('users/host/viewing_sessions/phone').roomCode,next.roomCode);
});
test('host target changes require a reset while display-title changes keep the same timeline',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 const renamed=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,target:{...target,title:'SMALLVILLE'},playback},d);
 assert.equal(renamed.playback.positionMs,playback.positionMs);
 const next=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:renamed.revision,target:{...target,episode:23},playback:{...playback,positionMs:0}},d);
 assert.equal(next.target.episode,23);assert.equal(next.playback.positionMs,0);
});
test('TV discovery rejects expired or future-dated registry entries',async()=>{
 const db=dbMock();member(db,'a');let time=now;const d=deps(db,()=>time);
 await viewingAction('a',{action:'register',deviceId:'tv',deviceKind:'tv'},d);
 time-=1;assert.deepEqual((await viewingAction('a',{action:'devices',deviceId:'phone'},d)).devices,[]);
 time=now+90000;assert.deepEqual((await viewingAction('a',{action:'devices',deviceId:'phone'},d)).devices,[]);
 await assert.rejects(viewingAction('a',{action:'handoff',deviceId:'phone',targetDeviceId:'tv',target,positionMs:0},d),{code:'TV_UNAVAILABLE'});
});
test('raw JSON, malformed/oversized payloads and method/auth failures have explicit bounded responses',async()=>{
 const db=dbMock();member(db,'alice');let status,payload;const res={setHeader(){},status(n){status=n;return this;},json(x){payload=x;}};
 const handler=createViewingHandler({...deps(db),verifyIdToken:async()=>({uid:'alice',firebase:{sign_in_provider:'password'}})});
 const request=body=>({method:'POST',headers:{authorization:'Bearer ok'},body});
 await handler(request(JSON.stringify({action:'register',deviceId:'phone'})),res);assert.equal(status,200);
 await handler(request('{broken'),res);assert.equal(status,400);assert.equal(payload.code,'INVALID_ACTION');
 await handler(request('x'.repeat(12001)),res);assert.equal(status,413);
 await handler({method:'GET',headers:{}},res);assert.equal(status,405);
 const unavailable=createViewingHandler({...deps(db),verifyIdToken:async()=>{throw Error('secret API token');}});
 await unavailable(request('{}'),res);assert.equal(status,401);assert.ok(!JSON.stringify(payload).includes('secret'));
});
test('failed invite guesses consume the rate budget even though room mutations roll back',async()=>{
 const db=dbMock();member(db,'guesser');let time=now;const d=deps(db,()=>time);
 const bad={action:'join',deviceId:'phone',roomCode:'AABBCCDDEE'};
 for(let i=0;i<8;i++)await assert.rejects(viewingAction('guesser',bad,d),{code:'PARTY_ENDED'});
 await assert.rejects(viewingAction('guesser',bad,d),{code:'SLOW_DOWN'});
 assert.ok([...db.data.keys()].every(path=>!path.startsWith('viewing_rooms/')&&!path.includes('/viewing_sessions/')));
 time+=1001;await assert.rejects(viewingAction('guesser',bad,d),{code:'PARTY_ENDED'});
});
test('concurrent host controls for the same revision commit exactly once',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 const control=playing=>viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing}},d);
 const results=await Promise.allSettled([control(false),control(true)]);
 assert.equal(results.filter(x=>x.status==='fulfilled').length,1);
 assert.equal(results.find(x=>x.status==='rejected').reason.code,'STALE_CONTROL');
 assert.equal(db.data.get('users/host/viewing_sessions/phone').revision,room.revision+1);
});
test('watch parties start at Basic while Mobile keeps same-account handoff and cannot host or join',async()=>{
 const db=dbMock();for(const [id,plan] of [['host','plan_basic'],['mobile','plan_mobile'],['standard','plan_standard']])member(db,id,plan);const d=deps(db);const room=await create(d);
 await assert.rejects(create(d,'mobile'),{code:'PARTY_PLAN_REQUIRED'});
 await assert.rejects(viewingAction('mobile',{action:'join',deviceId:'phone',roomCode:room.roomCode},d),{code:'PARTY_PLAN_REQUIRED'});
 await viewingAction('mobile',{action:'register',deviceId:'phone'},d);
 assert.equal((await viewingAction('standard',{action:'join',deviceId:'phone',roomCode:room.roomCode},d)).isHost,false);
 member(db,'host','plan_mobile');await assert.rejects(viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d),{code:'PARTY_PLAN_REQUIRED'});
 assert.equal((await viewingAction('host',{action:'end',deviceId:'phone',roomCode:room.roomCode},d)).ended,true);
});
test('expired members can clean up their own room but outsiders cannot end or leave it',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);member(db,'stranger');
 db.data.set('users/host/subscription/current',{planId:'plan_basic',status:'SUSPENDED',expiresAt:now+1});
 await assert.rejects(viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback},d),{code:'MEMBERSHIP_REQUIRED'});
 await assert.rejects(viewingAction('stranger',{action:'leave',deviceId:'phone',roomCode:room.roomCode},d),{code:'NOT_A_MEMBER'});
 assert.equal((await viewingAction('host',{action:'leave',deviceId:'phone',roomCode:room.roomCode},d)).ended,true);
});
test('comments derive author from membership, are idempotent, and cannot change playback revision',async()=>{
 const db=dbMock();member(db,'host','plan_basic');member(db,'guest','plan_basic');const d=deps(db);const room=await create(d);
 const joined=await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode,name:'Real guest'},d);
 assert.equal(joined.revision,room.revision);
 const message={action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:'one',text:'Hello 👏',uid:'host',name:'Spoof',memberId:room.memberId};
 const sent=await viewingAction('guest',message,d);const retry=await viewingAction('guest',message,d);
 assert.equal(sent.messages[0].name,'Real guest');assert.equal(sent.messages[0].memberId,joined.memberId);assert.equal(retry.messages.length,1);
 assert.equal(sent.revision,room.revision);assert.ok(sent.eventRevision>joined.eventRevision);assert.ok(!JSON.stringify(sent).includes('hostUid'));
 const paused=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:false}},d);
 assert.equal(paused.playback.playing,false);assert.equal(paused.messages.length,1);
 await assert.rejects(viewingAction('host',{...message,text:'Replace'},d),{code:'MESSAGE_CONFLICT'});
});
test('host moderation pauses new comments and removes messages without interrupting playback',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const room=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);
 const message={action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:'one',text:'Hi'};
 await viewingAction('guest',message,d);
 await assert.rejects(viewingAction('guest',{action:'moderate',deviceId:'phone',roomCode:room.roomCode,commentsEnabled:false},d),{code:'HOST_ONLY'});
 const stopped=await viewingAction('host',{action:'moderate',deviceId:'phone',roomCode:room.roomCode,commentsEnabled:false,deleteMessageId:'one'},d);
 assert.equal(stopped.commentsEnabled,false);assert.equal(stopped.messages.length,0);assert.equal(stopped.revision,room.revision);
 time+=1001;await assert.rejects(viewingAction('guest',{...message,messageId:'two'},d),{code:'COMMENTS_DISABLED'});
 await viewingAction('host',{action:'moderate',deviceId:'phone',roomCode:room.roomCode,commentsEnabled:true},d);
 time+=1001;assert.equal((await viewingAction('guest',{...message,messageId:'two'},d)).messages.length,1);
});
test('comment bursts have their own budget and never block host pause controls',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 const message={action:'comment',deviceId:'phone',roomCode:room.roomCode,text:'👏'};
 await viewingAction('host',{...message,messageId:'one'},d);await viewingAction('host',{...message,messageId:'two'},d);
 await assert.rejects(viewingAction('host',{...message,messageId:'three'},d),{code:'SLOW_DOWN'});
 assert.equal((await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:false}},d)).playback.playing,false);
});
test('comments reject malformed text, non-members, and cross-device claim reuse',async()=>{
 for(const text of ['', 'x'.repeat(161),'line\nbreak','bad\u0000text']){
  const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
  await assert.rejects(viewingAction('host',{action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:'one',text},d),{code:'INVALID_COMMENT'});
 }
 const db=dbMock();member(db,'host');member(db,'guest');const d=deps(db);const room=await create(d);
 await assert.rejects(viewingAction('guest',{action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:'one',text:'hi'},d),{code:'NOT_A_MEMBER'});
 await assert.rejects(viewingAction('host',{action:'comment',deviceId:'other',roomCode:room.roomCode,messageId:'one',text:'hi'},d),{code:'NOT_A_MEMBER'});
});
test('comment history is bounded and old messages never expand a snapshot indefinitely',async()=>{
 const db=dbMock();member(db,'host');let time=now;const d=deps(db,()=>time);const room=await create(d);
 for(let i=0;i<45;i++){time+=3100;await viewingAction('host',{action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:`m${i}`,text:'Hello'},d);}
 const snapshot=db.data.get('users/host/viewing_sessions/phone');assert.equal(snapshot.messages.length,40);assert.equal(snapshot.messages[0].id,'m5');assert.equal(snapshot.revision,room.revision);
});
test('timestamped controls compensate transit, clamp untrusted clocks and retry without reapplying',async()=>{
 const db=dbMock();member(db,'host');let time=now;const d=deps(db,()=>time);const room=await create(d);
 const control={action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,commandId:'pause1',sampledAtMs:time-350,playback:{...playback,playing:false}};
 const first=await viewingAction('host',control,d);assert.equal(first.updatedAtMs,time-350);
 time+=250;const retry=await viewingAction('host',control,d);assert.equal(retry.revision,first.revision);assert.equal(retry.updatedAtMs,first.updatedAtMs);
 const future=await viewingAction('host',{...control,expectedRevision:first.revision,commandId:'play1',sampledAtMs:time+100000,playback},d);assert.equal(future.updatedAtMs,time);
 const ancient=await viewingAction('host',{...control,expectedRevision:future.revision,commandId:'play2',sampledAtMs:time-100000,playback},d);assert.equal(ancient.updatedAtMs,time);
 const oldClient=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:ancient.revision,playback},d);assert.equal(oldClient.updatedAtMs,time);
});

test('repeated delivery retries remain idempotent and cannot bypass the separate chat rate budget',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 const message={action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:'retry',text:'Hello'};
 await viewingAction('host',message,d);const retry=await viewingAction('host',message,d);assert.equal(retry.messages.length,1);
 await assert.rejects(viewingAction('host',message,d),{code:'SLOW_DOWN'});
 const pause=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:false}},d);
 assert.equal(pause.playback.playing,false);assert.equal(pause.messages.length,1);
});

test('one transaction reads fresh membership once and commits rate admission with controls',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 db.usage.transactions=0;db.usage.reads.length=0;db.usage.writes.length=0;
 await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:false}},d);
 assert.equal(db.usage.transactions,1);
 assert.equal(db.usage.reads.filter(p=>p==='users/host/subscription/current').length,1);
 assert.equal(db.usage.reads.length,4);
 assert.equal(db.usage.writes.filter(p=>p.startsWith('viewing_rates/')).length,1);
 // No membership decision is cached between requests.
 db.data.set('users/host/subscription/current',{planId:'plan_basic',status:'SUSPENDED',expiresAt:now+10000});
 await assert.rejects(viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d),{code:'MEMBERSHIP_REQUIRED'});
});

test('validation after staging a detach cannot commit partial room or mirror changes',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const room=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);
 const before=new Map([...db.data].filter(([p])=>!p.startsWith('viewing_rates/')).map(([p,v])=>[p,structuredClone(v)]));
 time+=90001;db.usage.writes.length=0;
 await assert.rejects(viewingAction('host',{action:'create',deviceId:'phone',target:{...target,id:'bad'},playback},d),{code:'INVALID_TITLE'});
 assert.deepEqual(new Map([...db.data].filter(([p])=>!p.startsWith('viewing_rates/'))),before);
 assert.equal(db.usage.writes.length,1);assert.ok(db.usage.writes[0].startsWith('viewing_rates/'));
});

test('heartbeats reuse recent host presence without broadcasting history and renew before client timeout',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const room=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);
 time+=10000;
 const changed=await viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback},d);
 const snapshot=structuredClone(db.data.get('users/guest/viewing_sessions/phone'));
 time+=10000;db.usage.writes.length=0;
 const quiet=await viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d);
 assert.equal(quiet.eventRevision,changed.eventRevision);
 assert.equal(quiet.hostSeenAtMs,changed.hostSeenAtMs);
 assert.deepEqual(db.data.get('users/guest/viewing_sessions/phone'),snapshot);
 assert.equal(db.usage.writes.length,2); // Rate + canonical account lease only.
 assert.ok(!db.usage.writes.some(p=>p.startsWith('viewing_rooms/') || p.includes('/viewing_sessions/')));
 time+=10000;db.usage.writes.length=0;
 const renewed=await viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d);
 assert.equal(renewed.hostSeenAtMs,time);assert.equal(renewed.eventRevision,quiet.eventRevision+1);
 assert.equal(db.data.get('users/guest/viewing_sessions/phone').hostSeenAtMs,time);
 assert.ok(time-snapshot.hostSeenAtMs<45000);
 assert.equal(renewed.updatedAtMs,changed.updatedAtMs); // Presence must not shift the playback anchor.
});

test('duplicate deliveries, unchanged moderation and repeated joins do not rewrite every member snapshot',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const room=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);
 const control={action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,commandId:'once',playback};
 const changed=await viewingAction('host',control,d);
 const message={action:'comment',deviceId:'phone',roomCode:room.roomCode,messageId:'once',text:'Hi'};
 const sent=await viewingAction('guest',message,d);
 for (const [uid,body] of [['host',control],['guest',message],['host',{action:'moderate',deviceId:'phone',roomCode:room.roomCode,commentsEnabled:true,deleteMessageId:'missing'}],['guest',{action:'join',deviceId:'phone',roomCode:room.roomCode}]]) {
  time+=1001;db.usage.writes.length=0;
  const result=await viewingAction(uid,body,d);
  assert.equal(result.revision,changed.revision);assert.equal(result.eventRevision,sent.eventRevision);
  assert.ok(!db.usage.writes.some(p=>p.includes('/viewing_sessions/')));
  assert.equal(db.usage.writes.filter(p=>p.startsWith('viewing_rates/')).length,1);
  if (body.action!=='join') assert.ok(!db.usage.writes.some(p=>p.startsWith('viewing_rooms/')));
 }
});

test('a transaction retry rechecks a revoked subscription instead of reusing an earlier admission',async()=>{
 const db=dbMock();member(db,'host');const d=deps(db);const room=await create(d);
 const original=db.runTransaction;const before=structuredClone(db.data.get('viewing_rooms/'+room.roomCode));
 db.runTransaction=fn=>original(async tx=>{
  // First attempt loses a Firestore conflict; none of its buffered writes commit.
  const discarded=[];
  await fn({get:r=>tx.get(r),set:(...args)=>discarded.push(args)});
  assert.ok(discarded.some(([r])=>r.path.startsWith('viewing_rates/')));
  db.data.set('users/host/subscription/current',{planId:'plan_basic',status:'SUSPENDED',expiresAt:now+10000});
  return fn(tx);
 });
 await assert.rejects(viewingAction('host',{action:'control',deviceId:'phone',roomCode:room.roomCode,expectedRevision:room.revision,playback:{...playback,playing:false}},d),{code:'MEMBERSHIP_REQUIRED'});
 assert.deepEqual(db.data.get('viewing_rooms/'+room.roomCode),before);
});

test('host presence is renewed after a coalesced heartbeat before the existing 45-second timeout',async()=>{
 const db=dbMock();member(db,'host');member(db,'guest');let time=now;const d=deps(db,()=>time);const room=await create(d);
 await viewingAction('guest',{action:'join',deviceId:'phone',roomCode:room.roomCode},d);
 time+=11999;
 const quiet=await viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d);
 assert.equal(quiet.hostSeenAtMs,now);
 time+=20000;
 const fresh=await viewingAction('host',{action:'heartbeat',deviceId:'phone',roomCode:room.roomCode},d);
 assert.equal(time-now,31999);assert.equal(fresh.hostSeenAtMs,time);
 assert.equal(db.data.get('users/guest/viewing_sessions/phone').hostSeenAtMs,time);
});
