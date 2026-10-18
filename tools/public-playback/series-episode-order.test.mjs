import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {orderedProviderSeason,orderedEpisodeMetadata,joinOrderedEpisodes} from './series-episode-order.mjs';
import {PublicCatalogFlow} from './public-catalog-flow.mjs';
import {NoWarmFlow} from './no-warm-flow.mjs';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/episode-order/black-clover.json',import.meta.url),'utf8'));
const target={tmdbId:'73223',type:'tv',ott:'nf',title:'Black Clover',season:1,episode:1};
const html='<script type="application/ld+json">{"@type":"TVSeries","name":"Black Clover"}</script><select name="seasonSelect">'+['80237813','81313521','81399561','81582089'].map((id,i)=>`<option value="${id}">Season ${i+1}</option>`).join('')+'</select>';
test('all 170 verified identities survive translated titles and provider label drift',()=>{
 const metadata=orderedEpisodeMetadata(fixture.tmdb);
 const joined=[1,2,3,4].flatMap(s=>joinOrderedEpisodes(fixture.provider.filter(r=>r.s===`S${s}`),metadata,s));
 assert.equal(new Set(joined.map(r=>r.number)).size,170);assert.equal(new Set(joined.map(r=>r.id)).size,170);
 assert.deepEqual([1,2,3,4].map(s=>joined.filter(r=>r.providerSeason===s).length),[51,51,52,16]);
 assert.deepEqual(joined.find(r=>r.number===103),{number:103,id:'81399562',providerSeason:3,providerEpisode:104});
 assert.equal(joined.find(r=>r.number===170).id,'81582107');
});
test('ordering never applies to another series/catalog or accepts ambiguous metadata',()=>{
 assert.equal(orderedProviderSeason({...target,episode:103},'80238012'),3);
 assert.equal(orderedProviderSeason({...target,episode:103,ott:'pv'},'80238012'),null);
 assert.equal(orderedProviderSeason({...target,tmdbId:'42'},'80238012'),null);
 assert.throws(()=>orderedProviderSeason({...target,season:4,episode:52},'80238012'));
 assert.throws(()=>orderedEpisodeMetadata({...fixture.tmdb,id:1}));
 assert.throws(()=>joinOrderedEpisodes(fixture.provider.filter(r=>r.s==='S2'),orderedEpisodeMetadata(fixture.tmdb),1));
 const row=fixture.provider.find(r=>r.id==='81399562');assert.throws(()=>joinOrderedEpisodes([row,{...row,id:'99999999'}],orderedEpisodeMetadata(fixture.tmdb),3));
});
test('actual public flow resolves page six and every boundary by canonical title identity',async()=>{
 for(const [episode,id] of [[50,'80238275'],[51,'80238276'],[52,'81313232'],[102,'81313282'],[103,'81399562'],[154,'81408243'],[155,'81582092'],[170,'81582107']]) {
  const f=new PublicCatalogFlow({tmdbKey:'fixture'}),pages=[];
  f.request=async(stage,raw)=>{const u=new URL(raw);if(u.hostname==='www.netflix.com')return{body:html};if(u.hostname==='api.themoviedb.org')return{data:fixture.tmdb};throw Error('Unexpected request');};
  f.json=async(stage,path,params)=>{assert.equal(params.series,'80238012');const s={'80237813':1,'81313521':2,'81399561':3,'81582089':4}[params.s];const p=Number(params.page??1);pages.push(p);const rows=fixture.provider.filter(r=>r.s===`S${s}`);return{episodes:rows.slice((p-1)*10,p*10),nextPageShow:p*10<rows.length?1:0};};
  assert.equal(await f.episode({...target,episode},{id:'80238012'},{}),id);
  if([51,102,154].includes(episode))assert.ok(pages.includes(6));
 }
});
test('ordinary series pagination also reaches page six',async()=>{
 const f=new NoWarmFlow({seasonMappings:[{ott:'nf',showId:'80000000',season:1,seasonId:'80000001'}]}),seen=[];
 f.json=async(stage,path,params)=>{const page=Number(params.page??1);seen.push(page);return{episodes:[{id:String(80000100+page),s:'S1',ep:`E${page}`}],nextPageShow:page<6?1:0};};
 assert.equal(await f.episode({...target,tmdbId:'42',episode:6},{id:'80000000'},{}),'80000106');assert.deepEqual(seen,[1,2,3,4,5,6]);
});

test('public Netflix metadata uses the desktop request headers used by Android',async t=>{
 t.mock.method(globalThis,'fetch',async(url,options)=>{
  assert.equal(new URL(url).hostname,'www.netflix.com');
  assert.equal(options.headers['User-Agent'],'Mozilla/5.0');assert.equal(options.headers.Accept,'text/html');
  assert.equal(options.credentials,'omit');assert.equal(options.headers.Cookie,undefined);
  return new Response('<html>public title</html>',{status:200,headers:{'content-type':'text/html'}});
 });
 const f=new NoWarmFlow();f.deadline=Date.now()+10000;
 assert.equal((await f.request('public_seasons','https://www.netflix.com/title/80238012')).body,'<html>public title</html>');
 assert.equal(f.events[0].status,200);
});
