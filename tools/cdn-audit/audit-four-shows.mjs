import { Probe } from './probe.mjs';
import { writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
const args = process.argv.slice(2);
const option = key => { const i = args.indexOf(key); return i >= 0 ? args[i + 1] : undefined; };
if (args.includes('--help')) {
 console.log('node audit-four-shows.mjs [--base-url https://net52.cc] [--session-file PRIVATE/session.json] [--private-dir PRIVATE] [--report report.json]');
 process.exit(0);
}
let reportPath;
const probe=new Probe({appProfile:'tv',baseUrl:option('--base-url') || 'https://net52.cc',privateDir:option('--private-dir'),sessionFile:option('--session-file'),deadlineAt:Date.now()+240000,requestTimeoutMs:8000,handshakeTimeoutMs:58000,segmentCount:1,decodeSample:false});
const targets=[{title:'Smallville',year:2001,season:3,episode:1},{title:'Silo',year:2023,season:1,episode:1},{title:'Ironheart',year:2025,season:1,episode:1},{title:'Mr. Robot',year:2015,season:1,episode:1}];
const runs=[];
const terminal=new Set(['rate_limited','home_unavailable','addhash_missing','verification_cookie_missing','proxy_connect_failed','audit_budget_exceeded']);
const cleanPath=path=>path.split('/').map(p=>p.length>48?'[redacted]':p).join('/');
function sanitize(run){
 return {...run,events:run.events.map(e=>({...e,path:cleanPath(e.path)})),cdns:[...new Set(run.events.filter(e=>['video_manifest','video_segment','audio_manifest','audio_segment','video_init','audio_init'].includes(e.stage)).map(e=>e.host))],catalogRoute:probe.currentShow?{ott:probe.currentShow.ott,showId:probe.currentShow.id,episodeId:probe.currentId}:undefined};
}
await probe.init();
reportPath=resolve(option('--report') || join(probe.privateDir, 'four-shows-report.json'));
let blocked;
for(const target of targets){
 if(blocked){runs.push({...target,success:false,notAttempted:true,error:`shared_${blocked}`,events:[],cdns:[]});continue;}
 probe.currentShow=null;probe.currentId=null;
 console.log(`Starting ${target.title} S${target.season}E${target.episode}`);
 const run=sanitize(await probe.runTitle(target,probe.session?'warm_session_new_title':'cold'));
 runs.push(run);
 await writeFile(reportPath,JSON.stringify({checkedAt:new Date().toISOString(),scope:'Normal provider resolution with one bounded segment sample; not Android playback.',runs},null,2)+'\n');
 console.log(JSON.stringify({title:target.title,status:run.success?'media_sample_validated':run.error,totalMs:run.totalMs,cdns:run.cdns,catalogRoute:run.catalogRoute}));
 if(terminal.has(run.error))blocked=run.error;
}
await writeFile(reportPath,JSON.stringify({checkedAt:new Date().toISOString(),scope:'Normal provider resolution with one bounded segment sample; not Android playback.',runs},null,2)+'\n');
process.exitCode=runs.every(r=>r.success)?0:2;
