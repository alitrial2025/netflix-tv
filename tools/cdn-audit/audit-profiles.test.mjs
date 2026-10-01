import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { Probe, classify } from './probe.mjs';

const start = async handler => { const s = http.createServer(handler); await new Promise(r => s.listen(0, '127.0.0.1', r)); return [s, `http://127.0.0.1:${s.address().port}`]; };
const close = s => new Promise(r => s.close(r));
const target = { title: 'Smallville', year: 2001, season: 4, episode: 8 };

test('JSON master placeholders are not a rejected provider session', () => {
  assert.equal(classify('{"sources":[{"file":"/mobile/hls/id.m3u8?in=unknown::future"}]}', 200), 'json');
  assert.equal(classify('#EXTM3U\nvideo.m3u8?in=unknown', 200), 'session_rejected');
});

for (const [profile, renewal] of [['tv', false], ['mobile', false], ['mobile', true]]) test(`${profile}${renewal ? ' renewal' : ''}: preserve exact episode cookie, video/audio signatures and warm default audio`, async () => {
  let cdn; let base; const requests = []; let reject = false; let rejectedCookie = false;
  const [cdnServer, cdnUrl] = await start((req, res) => {
    const u = new URL(req.url, cdn); requests.push({ path: u.pathname, cookie: req.headers.cookie });
    assert.equal(req.headers.cookie, undefined, 'Provider cookies must never go to another CDN origin');
    if (reject) { res.statusCode = 403; res.end('Only valid users allowed'); return; }
    if (u.pathname === '/custom/video.m3u8') { assert.equal(u.searchParams.get('in'), 'ISSUED::su::myes'); res.end('#EXTM3U\n#EXTINF:5,\nv.jpg\n#EXT-X-ENDLIST'); }
    else if (u.pathname === '/audio/default.m3u8') { assert.equal(u.searchParams.get('in'), 'INDEPENDENT::future::audio'); res.end('#EXTM3U\n#EXTINF:5,\na.jpg\n#EXT-X-ENDLIST'); }
    else if (u.pathname.endsWith('.jpg')) { const bytes = Buffer.alloc(188 * 4); for (let i = 0; i < bytes.length; i += 188) bytes[i] = 0x47; res.statusCode = 206; res.end(bytes); }
    else { res.statusCode = 404; res.end('missing'); }
  }); cdn = cdnUrl.replace('127.0.0.1', 'localhost');
  const [providerServer, providerUrl] = await start((req, res) => {
    const u = new URL(req.url, base); requests.push({ path: u.pathname, cookie: req.headers.cookie });
    const json = x => res.end(JSON.stringify(x));
    if (renewal && !rejectedCookie && u.pathname.endsWith('/search.php')) {
      rejectedCookie = true; res.statusCode = 403; res.end('Session expired'); return;
    }
    if (u.pathname === '/mobile/home') { res.setHeader('Set-Cookie', 'addhash=SECRET; Path=/'); res.end('<html>var Qury="futureQury";</html>'); }
    else if (u.pathname === '/') { assert.equal(u.searchParams.get('futureQury'), 'SECRET'); res.statusCode = 204; res.end(); }
    else if (u.pathname === '/mobile/verify2.php') { res.setHeader('Set-Cookie', 't_hash_t=SECRET_SESSION; Path=/'); json({status:'ok'}); }
    else if (u.pathname.endsWith('/search.php')) json(u.pathname.includes('/pv/') ? {searchResult:[{id:'show',t:'Smallville',y:'2001'}]} : {searchResult:[]});
    else if (u.pathname.endsWith('/post.php')) json({season:[{id:'s4',s:'Season 4'}]});
    else if (u.pathname.endsWith('/episodes.php')) { assert.equal(u.searchParams.get('s'), 's4'); json({episodes:[{id:'exact-s4e8',ep:'Episode 8'}]}); }
    else if (u.pathname.endsWith('/playlist.php')) { assert.match(req.headers.cookie, /ott=pv/); assert.match(req.headers.cookie, /SEshow=exact-s4e8/); json({sources:[{file:'/mobile/pv/hls/exact-s4e8.m3u8?in=unknown::future'}]}); }
    else if (u.pathname.endsWith('.m3u8')) {
      assert.match(req.headers.cookie, /ott=pv/); assert.match(req.headers.cookie, /SEshow=exact-s4e8/);
      assert.equal(u.searchParams.get('in').includes('unknown'), false);
      res.end(`#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",DEFAULT=NO,URI="${cdn}/audio/wrong.m3u8"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",DEFAULT=YES,URI="${cdn}/audio/default.m3u8?in=INDEPENDENT::future::audio"\n#EXT-X-STREAM-INF:BANDWIDTH=100,AUDIO="sound"\n${cdn}/custom/video.m3u8?in=ISSUED::su::myes`);
    } else { res.statusCode = 404; res.end('missing'); }
  }); base = providerUrl;
  try {
    const p = new Probe({appProfile:profile,baseUrl:base,triggerBaseUrl:base,pollMs:0}); await p.init();
    const cold = await p.runTitle(target, 'cold'); assert.equal(cold.success, true); assert.equal(cold.handshakes, renewal ? 2 : 1); assert.equal(cold.separateAudioValidated, true);
    const warm = await p.runTitle(target, 'warm'); assert.equal(warm.success, true); assert.equal(warm.handshakes, 0); assert.equal(warm.separateAudioValidated, true);
    assert.equal(warm.events.some(e => e.stage === 'search' || e.stage.startsWith('handshake')), false);
    assert.equal(warm.events.some(e => e.path.startsWith('/mobile/')), profile === 'mobile');
    assert.equal(JSON.stringify([cold,warm]).includes('SECRET'), false);
    assert.equal(JSON.stringify([cold,warm]).includes('ISSUED'), false);
    reject = true; const bad = await p.runTitle(target, 'cdn_rejected'); assert.equal(bad.error, 'cdn_route_rejected'); assert.equal(bad.handshakes, 0); assert.ok(p.session);
    assert.equal(requests.some(r => r.path === '/audio/wrong.m3u8'), false);
  } finally { await close(providerServer); await close(cdnServer); }
});

test('original signature timestamp bounds expiry regardless of token mode', async () => {
  const { signatureExpiry } = await import('./probe.mjs');
  const now = Date.now(); const old = Math.floor((now - 11 * 3600000) / 1000);
  assert.ok(signatureExpiry(`https://cdn.invalid/media?in=hash1::hash2::${old}::future-mode::tail`, now) < now);
  assert.equal(signatureExpiry('https://cdn.invalid/media?in=opaque-signature', now), undefined);
  const future = Math.floor((now + 2 * 3600000) / 1000);
  assert.equal(signatureExpiry(`https://cdn.invalid/media?in=h1::h2::${future}::su::myes`, now), 0);
});
