const { createHash, randomBytes, randomUUID } = require('node:crypto');
const { PaymentError } = require('./payment-service.cjs');
const PLANS = new Set(['plan_mobile','plan_basic','plan_standard','plan_premium']);
const fail = (status, code, message) => { throw new PaymentError(status, code, message); };
const hash = value => createHash('sha256').update(value).digest('hex');
const validId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(value);
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const sameTitle = (a,b) => a.kind===b.kind && a.id===b.id && a.season===b.season && a.episode===b.episode;
function targetInput(value) {
  if (!value || !['movie','tv'].includes(value.kind) || !/^[1-9][0-9]{0,9}$/.test(String(value.id)) ||
      typeof value.title !== 'string' || !value.title.trim() || value.title.length > 200 ||
      !integer(value.season ?? 1,1,1000) || !integer(value.episode ?? 1,1,10000))
    fail(400,'INVALID_TITLE','Choose a movie or episode first.');
  return {kind:value.kind,id:String(value.id),title:value.title.trim(),season:value.season ?? 1,episode:value.episode ?? 1};
}
function playbackInput(value) {
  if (!value || !integer(value.positionMs,0,86400000) || !integer(value.durationMs,0,86400000) ||
      value.durationMs > 0 && value.positionMs > value.durationMs || typeof value.playing !== 'boolean' || typeof value.buffering !== 'boolean')
    fail(400,'INVALID_PLAYBACK','Playback position is unavailable.');
  return {positionMs:value.positionMs,durationMs:value.durationMs,playing:value.playing,buffering:value.buffering};
}
function allowed(sub, now, deviceKind) {
  return PLANS.has(sub?.planId) && ['ACTIVE','GRACE_PERIOD'].includes(String(sub.status).toUpperCase()) &&
    integer(sub.expiresAt,1,8640000000000000) && now < sub.expiresAt + 172800000 &&
    (deviceKind !== 'tv' || sub.planId !== 'plan_mobile');
}
const mirror = (db,uid,device) => db.collection('users').doc(uid).collection('viewing_sessions').doc(device);
const command = (db,uid,device) => db.collection('users').doc(uid).collection('viewing_commands').doc(device);
function publicRoom(room, uid, device, now) {
  const self = room.members.find(m => m.uid === uid && m.deviceId === device);
  return {roomCode:room.code,revision:room.revision,target:room.target,playback:room.playback,updatedAtMs:room.updatedAtMs,
    hostSeenAtMs:room.hostSeenAtMs,expiresAtMs:room.expiresAtMs,ended:room.ended === true,serverNowMs:now,
    isHost:room.hostUid === uid && room.hostDevice === device,memberId:self?.memberId ?? '',
    members:room.members.map(m=>({id:m.memberId,name:m.name,lastSeenAtMs:m.seenAtMs,isHost:m.uid === room.hostUid && m.deviceId === room.hostDevice}))};
}
function fanout(tx,db,room,now) {
  for (const member of room.members) tx.set(mirror(db,member.uid,member.deviceId),publicRoom(room,member.uid,member.deviceId,now));
}
async function viewingAction(uid, body, {db,now=Date.now,codeGenerator=()=>randomBytes(5).toString('hex').toUpperCase(),uuid=randomUUID}) {
  const action = body?.action, deviceId=body?.deviceId;
  if (!validId(deviceId) || !['register','devices','handoff','create','join','heartbeat','control','leave','end'].includes(action))
    fail(400,'INVALID_ACTION','This viewing action is unavailable.');
  const kind = body.deviceKind === 'tv' ? 'tv' : 'mobile';
  const time=now();
  const accountRef=db.collection('viewing_accounts').doc(hash(uid));
  const subRef=db.collection('users').doc(uid).collection('subscription').doc('current');
  const rateRef=db.collection('viewing_rates').doc(hash(uid));
  let code=['join','heartbeat','control','leave','end'].includes(action)?body.roomCode:null;
  if (action === 'create') code=codeGenerator();
  if (['create','join','heartbeat','control','leave','end'].includes(action) && !/^[A-F0-9]{10}$/.test(code || ''))
    fail(400,'INVALID_ROOM','Enter the ten-character party code.');
  const roomRef=code ? db.collection('viewing_rooms').doc(code) : null;
  // Commit rate admission independently, so rejected code guesses and stale
  // controls count too. An aborted room transaction must not erase the attempt.
  await db.runTransaction(async tx=>{
    const [subSnap,rateSnap]=await Promise.all([tx.get(subRef),tx.get(rateRef)]);
    const sub=subSnap.exists?subSnap.data():{};
    if (!allowed(sub,time,kind)) fail(403,'MEMBERSHIP_REQUIRED',kind==='tv'?'Use an active TV-enabled membership.':'Use an active membership to watch together.');
    const rate=rateSnap.exists?rateSnap.data():{};
    const recent=(rate.times||[]).filter(t=>integer(t,0,time)&&time-t<60000);
    if (recent.length>=120 || recent.filter(t=>time-t<1000).length>=8) fail(429,'SLOW_DOWN','Please wait a moment before trying again.');
    tx.set(rateRef,{times:[...recent,time],expiresAtMs:time+120000});
  });
  return db.runTransaction(async tx=>{
    // All membership, claim, and room reads precede writes.
    const [subSnap,accountSnap,roomSnap]=await Promise.all([tx.get(subRef),tx.get(accountRef),roomRef?tx.get(roomRef):null]);
    const sub=subSnap.exists?subSnap.data():{};
    if (!allowed(sub,time,kind)) fail(403,'MEMBERSHIP_REQUIRED',kind==='tv'?'Use an active TV-enabled membership.':'Use an active membership to watch together.');
    const account=accountSnap.exists?accountSnap.data():{};
    const claimed=account.rooms?.[deviceId];
    // Claims are leases. Read their canonical room before deciding whether an ended
    // or departed party should block a new one; never clear another party's claim.
    const previousRoom=claimed?.code && claimed.code!==code && ['create','join'].includes(action)
      ? await tx.get(db.collection('viewing_rooms').doc(claimed.code)) : null;
    const previous=previousRoom?.exists?previousRoom.data():null;
    const claimLive=claimed && claimed.code!==code && claimed.expiresAtMs>time &&
      integer(claimed.seenAtMs,0,time) && time-claimed.seenAtMs<90000 && previous && !previous.ended && time<previous.expiresAtMs;
    const detachPrevious=()=>{
      if (!previous || previous.ended) return;
      const oldSelf=previous.members.find(m=>m.uid===uid&&m.deviceId===deviceId);
      if (!oldSelf) return;
      if (previous.hostUid===uid&&previous.hostDevice===deviceId) previous.ended=true;
      else previous.members=previous.members.filter(m=>m!==oldSelf);
      previous.revision++;
      tx.set(db.collection('viewing_rooms').doc(claimed.code),previous);
      fanout(tx,db,previous,time);
    };
    let devices=(account.devices||[]).filter(d=>validId(d.id)&&integer(d.seenAtMs,0,time)&&time-d.seenAtMs<90000);
    const result={serverNowMs:time};
    if (action==='register') {
      const name=String(body.name|| (kind==='tv'?'NetflixPro TV':'NetflixPro phone')).trim().slice(0,60);
      devices=devices.filter(d=>d.id!==deviceId);
      if (devices.length>=8) fail(409,'DEVICE_LIMIT','Close NetflixPro on an unused device and try again.');
      devices.push({id:deviceId,kind,name,seenAtMs:time});
      tx.set(accountRef,{devices,updatedAtMs:time},{merge:true});
    } else if (action==='devices') {
      result.devices=devices.filter(d=>d.kind==='tv'&&d.id!==deviceId).map(d=>({id:d.id,name:d.name}));
    } else if (action==='handoff') {
      if (!validId(body.targetDeviceId) || !devices.some(d=>d.id===body.targetDeviceId&&d.kind==='tv')) fail(409,'TV_UNAVAILABLE','Open NetflixPro on your signed-in TV, then refresh.');
      const target=targetInput(body.target),position=body.positionMs;
      if (!integer(position,0,86400000)) fail(400,'INVALID_PLAYBACK','Playback position is unavailable.');
      tx.set(command(db,uid,body.targetDeviceId),{id:uuid(),target,positionMs:position,issuedAtMs:time,expiresAtMs:time+30000});
    } else {
      let room=roomSnap?.exists?roomSnap.data():null;
      let publish=true;
      if (action==='create') {
        if (room) fail(409,'CODE_COLLISION','Please create the party again.');
        if (claimLive) fail(409,'ALREADY_IN_PARTY','Leave your current party before creating another.');
        detachPrevious();
        room={code,hostUid:uid,hostDevice:deviceId,target:targetInput(body.target),playback:playbackInput(body.playback),
          createdAtMs:time,updatedAtMs:time,hostSeenAtMs:time,expiresAtMs:time+21600000,revision:1,
          members:[{uid,deviceId,memberId:uuid(),name:String(body.name||'Host').trim().slice(0,40),seenAtMs:time}],ended:false};
        tx.set(accountRef,{...account,rooms:{...(account.rooms||{}),[deviceId]:{code,expiresAtMs:room.expiresAtMs,seenAtMs:time}}});
      } else {
        if (!room || room.ended || time>=room.expiresAtMs) fail(404,'PARTY_ENDED','This party has ended or its code has expired.');
        const self=room.members.find(m=>m.uid===uid&&m.deviceId===deviceId);
        const host=room.hostUid===uid&&room.hostDevice===deviceId;
        if (action==='join') {
          if (claimLive) fail(409,'ALREADY_IN_PARTY','Leave your current party before joining another.');
          if (!self) {
            room.members=room.members.filter(m=>(m.uid===room.hostUid&&m.deviceId===room.hostDevice)||time-m.seenAtMs<90000);
            if (room.members.length>=8) fail(409,'PARTY_FULL','This party already has eight viewers.');
            room.members.push({uid,deviceId,memberId:uuid(),name:String(body.name||'Viewer').trim().slice(0,40),seenAtMs:time});
          } else self.seenAtMs=time;
          detachPrevious();
          room.revision++;
          tx.set(accountRef,{...account,rooms:{...(account.rooms||{}),[deviceId]:{code,expiresAtMs:room.expiresAtMs,seenAtMs:time}}});
        } else {
          if (!self || !['leave','end'].includes(action) && account.rooms?.[deviceId]?.code!==code)
            fail(403,'NOT_A_MEMBER','Join the party before using its controls.');
          self.seenAtMs=time;
          if (action==='control') {
            if (!host) fail(403,'HOST_ONLY','The host controls party playback.');
            if (!integer(body.expectedRevision,1,Number.MAX_SAFE_INTEGER) || body.expectedRevision!==room.revision)
              fail(409,'STALE_CONTROL','The party changed. Reconnect to sync playback.');
            const target=body.target?targetInput(body.target):room.target;
            const same=sameTitle(target,room.target);
            // A changed episode must reset its position explicitly; never carry the old episode's seek target.
            if (!same && body.playback?.positionMs!==0) fail(400,'INVALID_PLAYBACK','A new party title must start at its beginning.');
            room.target=target;room.playback=playbackInput(body.playback);room.updatedAtMs=time;room.hostSeenAtMs=time;room.revision++;
          } else if (action==='heartbeat') {
            if (host) room.hostSeenAtMs=time; else publish=false;
            tx.set(accountRef,{...account,rooms:{...(account.rooms||{}),[deviceId]:{code,expiresAtMs:room.expiresAtMs,seenAtMs:time}}});
          } else if (action==='end' || action==='leave'&&host) {
            if (!host) fail(403,'HOST_ONLY','Only the host can end this party.');
            room.ended=true;room.revision++;
            // Other viewers may already be in a new room. Their next create/join
            // checks this ended room, instead of wiping newer account leases.
            if (account.rooms?.[deviceId]?.code===code)
              tx.set(accountRef,{...account,rooms:{...(account.rooms||{}),[deviceId]:null}});
          } else if (action==='leave') {
            room.members=room.members.filter(m=>m!==self);room.revision++;
            tx.set(mirror(db,uid,deviceId),{ended:true,roomCode:code,serverNowMs:time});
            if (account.rooms?.[deviceId]?.code===code)
              tx.set(accountRef,{...account,rooms:{...(account.rooms||{}),[deviceId]:null}});
          }
        }
      }
      tx.set(roomRef,room);if (publish) fanout(tx,db,room,time);
      Object.assign(result,publicRoom(room,uid,deviceId,time));
    }
    return result;
  });
}
function createViewingHandler(deps) {
  return async(req,res)=>{
    res.setHeader('Cache-Control','no-store');
    if(req.method!=='POST') return res.status(405).json({code:'METHOD_NOT_ALLOWED',message:'Use POST.'});
    try {
      const token=/^Bearer ([^\s]+)$/.exec(req.headers?.authorization||'')?.[1];
      if(!token) fail(401,'SIGN_IN_REQUIRED','Sign in to watch together.');
      let identity;try{identity=await deps.verifyIdToken(token);}catch(_){fail(401,'SIGN_IN_REQUIRED','Sign in again to watch together.');}
      if(!identity?.uid||identity.firebase?.sign_in_provider==='anonymous') fail(401,'SIGN_IN_REQUIRED','Sign in to watch together.');
      const raw=typeof req.body==='string'?req.body:JSON.stringify(req.body||{});
      if(Buffer.byteLength(raw)>12000) fail(413,'PAYLOAD_TOO_LARGE','This viewing request is too large.');
      let body;try{body=typeof req.body==='string'?JSON.parse(req.body):req.body;}catch(_){fail(400,'INVALID_ACTION','Invalid viewing request.');}
      const result=await viewingAction(identity.uid,body,deps);return res.status(200).json(result);
    } catch(error) {
      const known=error instanceof PaymentError;
      return res.status(known?error.status:503).json({code:known?error.code:'VIEWING_UNAVAILABLE',message:known?error.message:'Could not connect. Your playback and downloads are unchanged.'});
    }
  };
}
module.exports={targetInput,playbackInput,allowed,viewingAction,createViewingHandler};
