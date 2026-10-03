import {NoWarmFlow} from './no-warm-flow.mjs';
import {writeFile} from 'node:fs/promises';
const results=[];
for(const target of [{ott:'hs',title:'Game of Thrones',year:2011,type:'tv',season:1,episode:1,nativeId:'1971002880',identitySource:'https://www.hotstar.com/in/shows/game-of-thrones/1971002880'},{ott:'hs',title:'Lanterns',year:2026,type:'tv',season:1,episode:1,nativeId:'1271680756',identitySource:'https://www.airtelxstream.in/tv-shows/lanterns/HOTSTAR_DTH_TVSHOW_1271680756'}]){
const flow=new NoWarmFlow({appClientMode:true,titleTimeoutMs:60000});
flow.search=async t=>{const d=await flow.json('public_identity_validation',`/mobile/${t.ott}/post.php`,{id:t.nativeId});if(d.status!=='y'||d.type!=='t'||d.title.toLowerCase()!==t.title.toLowerCase())throw new Error('Identity mismatch');return{id:t.nativeId,yearVerified:d.year==t.year}};
const result=await flow.run(target);results.push(result);console.log(JSON.stringify(result));
}
await writeFile(new URL('./public-id-segment-report.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),cookieFree:true,results},null,2));
