import test from 'node:test';
import assert from 'node:assert/strict';
import {hotstarIds, primeSeasons, netflixSeasons, PublicCatalogFlow} from './public-catalog-flow.mjs';
const claim = (value,rank='normal') => ({rank,mainsnak:{datavalue:{value}}});
test('external IDs accept official catalog identities and reject deprecated and misleading sites', () => {
  const json = {entities:{Q1:{claims:{P11049:[claim('1260022894'),claim('999999','deprecated')],
    P856:[claim('https://www.hotstar.com/in/shows/special-ops/1260022894'),
      claim('https://hotstar.com.evil.test/show/123456'),claim('http://hotstar.com/show/123456')]}}}};
  assert.deepEqual(hotstarIds(json,'Q1'),['1260022894']);
});
function prime(rows) {return `<script>${JSON.stringify({init:{preparations:{body:{atf:{state:{
  detail:{headerDetail:{show:{titleType:'season',title:'Slow Horses - Season 1'}}},seasons:{show:rows}}}}}}})}</script>`}
test('Prime public seasons require exact show identity, trusted host and unambiguous numbering', () => {
  const rows = [{sequenceNumber:1,seasonLink:'/detail/0MEYJKN34E2DBOY4OY7Z31N4Q4'},
    {sequenceNumber:2,seasonLink:'https://evil.test/detail/0AAAAAAAAAAAAAAAAAAAAAAAAA'}];
  assert.deepEqual([...primeSeasons(prime(rows),'Slow Horses')],[[1,'0MEYJKN34E2DBOY4OY7Z31N4Q4']]);
  assert.throws(() => primeSeasons(prime(rows),'Other series'));
  assert.throws(() => primeSeasons(prime([...rows,{sequenceNumber:1,seasonLink:'/detail/0BBBBBBBBBBBBBBBBBBBBBBBBB'}]),'Slow Horses'));
});
test('Netflix season numbers come from the public selector, never an assumed arithmetic ID', () => {
  const html = '<script type="application/ld+json">{"@type":"TVSeries","name":"Smallville"}</script>' +
    '<select name="seasonSelect"><option value="70155584">Season 1</option><option value="80000012">Season 2</option></select>';
  assert.deepEqual([...netflixSeasons(html,'Smallville')],[[1,'70155584'],[2,'80000012']]);
  assert.throws(() => netflixSeasons(html,'Other series'));
  assert.throws(() => netflixSeasons(html.replace('Season 2','Season 1'),'Smallville'));
});
test('a new show absent from the snapshot is discovered and its verified episode metadata is reused', async () => {
  const flow=new PublicCatalogFlow({tmdbKey:'fixture'}), calls=[];
  const path='/tv-shows/new-series/HOTSTAR_DTH_TVSHOW_1971999001';
  const details={status:'y',title:'New Series',type:'t',year:'2026',episodes:[{id:'1971999002',s:'1',ep:'1'}]};
  flow.request=async(stage,raw)=>{
    const u=new URL(raw);calls.push(u.pathname);
    if(u.pathname.endsWith('search.php')) return {data:{head:'Top Searches'},body:'{}'};
    if(u.pathname==='/tv-shows') return {body:`<a href="${path}">New</a>`};
    if(u.pathname===path) return {body:'<p id="banner-content-release-year">2026</p><script>{"@type":"VideoObject","name":"New Series"}</script>'};
    if(u.pathname.endsWith('/post.php')) return {data:details,body:JSON.stringify(details)};
    throw Error('Unexpected request: '+u.pathname);
  };
  const title={title:'New Series',year:2025,type:'tv',tmdbId:'999999001',season:1,episode:1};
  const identity=await flow.search(title);
  assert.equal(identity.id,'1971999001');assert.equal(title.ott,'hs');
  assert.equal(await flow.episode(title,identity,{}),'1971999002');
  assert.equal(calls.filter(p=>p.endsWith('/post.php')).length,1);
  assert.ok(!calls.some(p=>p.includes('wikidata') || p.endsWith('external_ids')));
});
