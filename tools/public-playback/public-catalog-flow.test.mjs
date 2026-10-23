import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {primeDetailId} from './prime-public-url.mjs';
import {NoWarmFlow} from './no-warm-flow.mjs';
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
test('Prime regional Expanse selector retains all six explicitly numbered seasons', () => {
  const fixture = readFileSync(new URL('../../app/src/test/resources/provider/expanse-prime-regional.json', import.meta.url), 'utf8');
  const seasons = primeSeasons(`<script>${fixture}</script>`, 'The Expanse');
  assert.deepEqual([...seasons.keys()], [1,2,3,4,5,6]);
  assert.equal(seasons.get(5), '0OBDSPAICFE4VNWZNFPW2I8TYU');
});
test('Prime detail URLs retain exact IDs across locale, region and title routes', () => {
  const id = '0TZXL00XCKTUHFBO67U01EGMCX';
  for (const path of [`/detail/${id}`, `/-/en/detail/${id}/`, `/region/eu/detail/${id}`,
    `/-/en/region/eu/detail/The-Expanse/${id}?language=en`]) assert.equal(primeDetailId(path), id);
  for (const url of [`http://www.primevideo.com/detail/${id}`, `https://user:pass@www.primevideo.com/detail/${id}`,
    `https://www.primevideo.com:444/detail/${id}`, `https://www.primevideo.com.evil.test/detail/${id}`,
    `https://www.primevideo.com/auth/redirect/detail/${id}`, `/search?id=${id}`]) assert.equal(primeDetailId(url), null);
});
test('Prime regional selectors cannot import recommendation seasons or unsafe title links', () => {
  const state = {detail:{headerDetail:{show:{titleType:'season',title:'The Expanse - Season 1'}}},
    seasons:{show:[{sequenceNumber:1,seasonLink:'/region/eu/detail/0TZXL00XCKTUHFBO67U01EGMCX'},
      {sequenceNumber:2,seasonLink:'http://www.primevideo.com/region/eu/detail/0HIYALAUVS1FS8HVF299SZONEY'}],
      recommendation:[{sequenceNumber:2,seasonLink:'/region/eu/detail/0UNRELATED12345'}]}};
  const html = `<script>${JSON.stringify({init:{preparations:{body:{atf:{state}}}}})}</script>`;
  assert.deepEqual([...primeSeasons(html,'The Expanse')], [[1,'0TZXL00XCKTUHFBO67U01EGMCX']]);
});
test('HTTP runtime follows only bounded same-title official Prime redirects', async () => {
  const originalFetch = globalThis.fetch;
  const id = '0TZXL00XCKTUHFBO67U01EGMCX';
  try {
    for (const status of [301,302,303,307,308]) {
      const seen = [];
      globalThis.fetch = async (url, options) => {
        seen.push(url.href);
        assert.equal(options.redirect, 'manual'); assert.equal(options.credentials, 'omit');
        assert.equal(options.headers['User-Agent'], 'Mozilla/5.0');
        assert.equal(options.headers.Cookie, undefined); assert.equal(options.headers.Authorization, undefined);
        return url.pathname === `/detail/${id}` ? new Response(null,{status,headers:{location:`/-/en/region/eu/detail/${id}?language=en`}}) : new Response('verified metadata');
      };
      const flow = new NoWarmFlow(); flow.deadline = Date.now()+10000;
      assert.equal((await flow.request('public_seasons',`https://www.primevideo.com/detail/${id}`)).body,'verified metadata');
      assert.equal(seen.length,2);
    }
    for (const location of ['',`http://www.primevideo.com/detail/${id}`,`https://evil.test/detail/${id}`,
      `https://user:password@www.primevideo.com/detail/${id}`, `/region/eu/detail/0WRONGTITLE12345`, `/auth/redirect?id=${id}`, `/detail/${id}`]) {
      let calls = 0;
      globalThis.fetch = async () => {calls++; return new Response(null,{status:307,headers:{location}});};
      const flow = new NoWarmFlow(); flow.deadline=Date.now()+10000;
      await assert.rejects(flow.request('public_seasons',`https://www.primevideo.com/detail/${id}`),{code:'invalid_public_catalog_redirect'});
      assert.equal(calls,1);
    }
    let calls=0;
    globalThis.fetch = async () => new Response(null,{status:307,headers:{location:`/region/eu/detail/${id}?hop=${++calls}`}});
    const flow = new NoWarmFlow(); flow.deadline=Date.now()+10000;
    await assert.rejects(flow.request('public_seasons',`https://www.primevideo.com/detail/${id}`),{code:'invalid_public_catalog_redirect'});
    assert.equal(calls,4);
  } finally {globalThis.fetch=originalFetch;}
});
test('HTTP runtime uses one explicit English fallback for unavailable Prime metadata, preserving auth and limits', async () => {
  const originalFetch = globalThis.fetch;
  const id = '0TZXL00XCKTUHFBO67U01EGMCX';
  try {
    for (const status of [502,503,504,401,403,429]) {
      const seen=[];
      globalThis.fetch = async url => {
        seen.push(url.href);
        return new Response(url.pathname.startsWith('/-/en/') ? 'verified metadata' : '',{status:url.pathname.startsWith('/-/en/') ? 200 : status});
      };
      const flow = new NoWarmFlow(); flow.deadline=Date.now()+10000;
      if ([502,503,504].includes(status)) {
        assert.equal((await flow.request('public_seasons',`https://www.primevideo.com/detail/${id}`)).body,'verified metadata');
        assert.equal(seen.length,2); assert.equal(new URL(seen[1]).pathname,`/-/en/detail/${id}`);
      } else {
        await assert.rejects(flow.request('public_seasons',`https://www.primevideo.com/detail/${id}`)); assert.equal(seen.length,1);
      }
    }
    let calls=0;
    globalThis.fetch = async () => {calls++; return new Response('',{status:503});};
    const flow=new NoWarmFlow();flow.deadline=Date.now()+10000;
    await assert.rejects(flow.request('public_seasons',`https://www.primevideo.com/detail/${id}`));assert.equal(calls,2);
  } finally {globalThis.fetch=originalFetch;}
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
  const details={status:'y',title:'New Series',type:'t',year:'2026',first_air_date:'2025-01-01',episodes:[{id:'1971999002',s:'1',ep:'1'}]};
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


test('HTTP runtime: unavailable primary and unsigned waiting clip retain the working issued fallback', async t => {
  const requests = [];
  const server = createServer((request, response) => {
    requests.push({path: new URL(request.url, 'http://fixture').pathname, headers: request.headers});
    if (request.url === '/newtv/player.php?id=1271684191') { response.writeHead(403); response.end('Page Not Found'); return; }
    if (request.url === '/mobile/hs/hls/1271684191.m3u8') {
      response.end('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8'); return;
    }
    if (request.url.startsWith('/mobile/hs/playlist.php?')) {
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({sources:[{file:`http://127.0.0.1:${server.address().port}/issued.m3u8?in=provider-issued`}]})); return;
    }
    if (request.url === '/issued.m3u8?in=provider-issued') { response.end('#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST'); return; }
    response.writeHead(500); response.end('Unexpected request');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  const primary = new NoWarmFlow({baseUrl, allowLocalFixtures:true, appClientMode:true}); primary.deadline = Date.now()+5000;
  const legacy = new NoWarmFlow({baseUrl, allowLocalFixtures:true, appClientMode:true}); legacy.deadline = Date.now()+5000;
  await assert.rejects(primary.request('primary', `${baseUrl}/newtv/player.php?id=1271684191`), error => error.code === 'authorization_required');
  await assert.rejects(legacy.request('direct_probe', `${baseUrl}/mobile/hs/hls/1271684191.m3u8`), error => error.code === 'unsigned_probe_rejected');
  assert.equal(legacy.rateLimited, false);
  const target = {ott:'hs',title:'Lanterns',type:'tv',season:1,episode:1};
  const run = {};
  const issued = await legacy.discover(target, '1271684191', run);
  const manifest = await legacy.request('issued_master', issued);
  assert.match(manifest.body, /#EXTINF:/);
  assert.equal(new URL(issued).searchParams.get('in'), 'provider-issued');
  assert.deepEqual(requests.map(request => request.path), ['/newtv/player.php','/mobile/hs/hls/1271684191.m3u8','/mobile/hs/playlist.php','/issued.m3u8']);
  assert.ok(requests.every(request => !request.headers.cookie && !request.headers.authorization));
});

test('HTTP runtime: actual provider 429 and server Retry-After stop same-provider fallback', async t => {
  for (const code of [429,200]) {
    let requests = 0;
    const server = createServer((request,response) => {
      requests++; response.writeHead(code, {'Retry-After':'120'});
      response.end(code === 429 ? 'busy' : '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8');
    });
    await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
    const baseUrl=`http://127.0.0.1:${server.address().port}`;
    const flow=new NoWarmFlow({baseUrl,allowLocalFixtures:true}); flow.deadline=Date.now()+5000;
    try {
      await assert.rejects(flow.request('direct_probe', `${baseUrl}/mobile/hs/hls/1271684191.m3u8`), error => ['rate_limited','waiting_video_returned'].includes(error.code));
      await assert.rejects(flow.json('playlist','/mobile/hs/playlist.php',{id:'1271684191'}), error => error.code === 'provider_cooldown');
      assert.equal(requests,1);
      assert.equal(flow.events[0].retryAfter,'120');
    } finally { await new Promise(resolve => server.close(resolve)); }
  }
});
