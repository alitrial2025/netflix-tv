const {writeFileSync}=require('node:fs');
const {execFileSync}=require('node:child_process');
const {resolve,dirname}=require('node:path');
const Module=require('node:module');
const repo=resolve(__dirname,'../..');
const option=name=>{const i=process.argv.indexOf(name);return i<0?undefined:process.argv[i+1];};
const baseline=option('--baseline');
if(!/^[a-f0-9]{40}$/.test(baseline||''))throw Error('Pass --baseline <full commit SHA>; optionally --report <path>.');
const service=resolve(repo,'marketing-site/server/viewing-service.cjs');
const baselineModule=new Module(service,module);baselineModule.filename=service;baselineModule.paths=Module._nodeModulePaths(dirname(service));
baselineModule._compile(execFileSync('git',['show',baseline+':marketing-site/server/viewing-service.cjs'],{cwd:repo,encoding:'utf8'}),service);
const versions={before:baselineModule.exports.viewingAction,after:require(service).viewingAction};
function database() {
 const data=new Map(),usage={transactions:0,reads:0,writes:0,mirrorWrites:0,mirrorJsonBytes:0};
 const doc=path=>({path,collection:name=>collection(path+'/'+name)}),collection=path=>({doc:id=>doc(path+'/'+id)});
 const merge=(a,b)=>{const v={...a};for(const[k,x]of Object.entries(b))v[k]=x&&typeof x==='object'&&!Array.isArray(x)?merge(a?.[k]||{},x):x;return v;};
 return {data,usage,collection,runTransaction:async fn=>{
  usage.transactions++;const writes=[];
  const result=await fn({get:async r=>{usage.reads++;if(writes.length)throw Error('Read after write');return {exists:data.has(r.path),data:()=>structuredClone(data.get(r.path))};},set:(r,v,o)=>writes.push([r.path,structuredClone(v),o])});
  for(const[p,v,o]of writes){usage.writes++;data.set(p,o?.merge?merge(data.get(p),v):v);if(p.includes('/viewing_sessions/')){usage.mirrorWrites++;usage.mirrorJsonBytes+=Buffer.byteLength(JSON.stringify(v));}}
  return result;
 }};
}
async function simulate(action,viewers,controls,chat) {
 const db=database();let time=1800000000000,id=0,requests=0;
 const deps={db,now:()=>time,codeGenerator:()=>String(++id).padStart(10,'A'),uuid:()=>`member-${++id}`};
 const target={kind:'tv',id:'4604',title:'Smallville',season:6,episode:22};
 const playback={positionMs:0,durationMs:7200000,playing:controls,buffering:false};
 const call=async(uid,body)=>{requests++;return action(uid,{deviceId:'device',deviceKind:'tv',...body},deps);};
 for(let i=0;i<viewers;i++)db.data.set(`users/u${i}/subscription/current`,{planId:'plan_basic',status:'ACTIVE',expiresAt:time+86400000});
 let room=await call('u0',{action:'create',name:'Host',target,playback});
 for(let i=1;i<viewers;i++)await call('u'+i,{action:'join',name:'Viewer '+i,roomCode:room.roomCode});
 for(const key of Object.keys(db.usage))db.usage[key]=0;requests=0;
 for(let second=1;second<=3600;second++){
  time=1800000000000+second*1000;
  if(controls&&second%10===0)room=await call('u0',{action:'control',roomCode:room.roomCode,commandId:'sync-'+second,expectedRevision:room.revision,playback:{...playback,positionMs:second*1000}});
  for(let i=0;i<viewers;i++){
   if(second%40===3+i)await call('u'+i,{action:'register',name:i?'Viewer '+i:'Host'});
   if(second%20===7+i)await call('u'+i,{action:'heartbeat',roomCode:room.roomCode});
  }
  if(chat&&second%30===1)await call('u'+(Math.floor(second/30)%viewers),{action:'comment',roomCode:room.roomCode,messageId:'chat-'+second,text:'Enjoying this episode together!'});
 }
 return {requests,...db.usage};
}
(async()=>{
 const report={baseline,scope:'One-hour deterministic simulation. Includes 20-second heartbeats, 40-second registration, optional 10-second controls and two comments/minute. Counts exclude setup and transaction retries; JSON mirror bytes are payload estimates, not measured production transfer.',scenarios:[]};
 for(const s of [{viewers:2,controls:true,chat:true},{viewers:8,controls:true,chat:true},{viewers:8,controls:false,chat:false}]){
  const result={...s,before:await simulate(versions.before,s.viewers,s.controls,s.chat),after:await simulate(versions.after,s.viewers,s.controls,s.chat),reductionPercent:{}};
  for(const k of ['transactions','reads','writes','mirrorWrites','mirrorJsonBytes'])result.reductionPercent[k]=Number((100*(1-result.after[k]/result.before[k])).toFixed(1));
  report.scenarios.push(result);
 }
 if(option('--report'))writeFileSync(option('--report'),JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1});
