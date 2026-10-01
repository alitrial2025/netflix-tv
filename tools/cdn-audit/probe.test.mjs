import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Probe, classify, segmentKind, playlistEntries } from './probe.mjs';

test('HLS and container checks reject successful HTML and malformed payloads', () => {
  assert.equal(classify('<html>please log in</html>', 200), 'html');
  assert.equal(classify('#EXTM3U\n/files/220884/video.m3u8', 200), 'rate_limited');
  assert.equal(classify('denied', 403), 'session_rejected');
  assert.equal(segmentKind(Buffer.from('<html>not video</html>')), 'unrecognized');
  const packet = Buffer.alloc(188 * 4); for (let i = 0; i < packet.length; i += 188) packet[i] = 0x47;
  assert.equal(segmentKind(packet), 'mpeg_ts');
  assert.deepEqual(playlistEntries('#EXTM3U\n#EXTINF:10,\nsegment.jpg', 'https://cdn.invalid/title/video.m3u8?in=SECRET'), ['https://cdn.invalid/title/segment.jpg']);
});

test('normal cold flow selects exact Smallville S4E8 then warm repeat skips handshake and catalog discovery', async () => {
  let base; const requests = [];
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, base); requests.push(u.pathname);
    const json = value => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
    if (u.pathname === '/mobile/home') { res.setHeader('Set-Cookie', 'addhash=SECRET_ADDHASH; Path=/'); res.end('<html>var Qury="hee5"; var Vsite="userver";</html>'); }
    else if (u.pathname === '/') { res.statusCode = 204; res.end(); }
    else if (u.pathname === '/mobile/verify2.php') { res.setHeader('Set-Cookie', 't_hash_t=SECRET_SESSION; Path=/'); json({ status: 'ok' }); }
    else if (u.pathname === '/mobile/search.php') json({ searchResult: [{ id: 'smallville', t: 'Smallville', y: '2001' }] });
    else if (u.pathname === '/mobile/post.php') json({ season: [{ id: 'season4', s: 'Season 4' }] });
    else if (u.pathname === '/mobile/episodes.php') json({ episodes: [{ id: 'exact-s4e8', ep: 'Episode 8' }], nextPageShow: 0 });
    else if (u.pathname === '/mobile/playlist.php') { assert.equal(u.searchParams.get('id'), 'exact-s4e8'); json({ sources: [{ file: '/mobile/hls/exact-s4e8.m3u8?in=SECRET_TOKEN' }], tracks: [] }); }
    else if (u.pathname === '/mobile/hls/exact-s4e8.m3u8') res.end('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\n/custom/full-hd/video.m3u8?in=SECRET_TOKEN');
    else if (u.pathname === '/custom/full-hd/video.m3u8') res.end('#EXTM3U\n#EXTINF:10,\nfirst.jpg\n#EXTINF:10,\nsecond.jpg\n#EXT-X-ENDLIST');
    else if (u.pathname.endsWith('.jpg')) { const bytes = Buffer.alloc(188 * 4); for (let i = 0; i < bytes.length; i += 188) bytes[i] = 0x47; res.statusCode = 206; res.end(bytes); }
    else { res.statusCode = 404; res.end('missing'); }
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r)); base = `http://127.0.0.1:${server.address().port}`;
  try {
    const dir = await mkdtemp(join(tmpdir(), 'cdn-fixture-')); const p = new Probe({ baseUrl: base, triggerBaseUrl: base, privateDir: dir, pollMs: 0 }); await p.init();
    const target = { title: 'Smallville', year: 2001, season: 4, episode: 8 };
    const cold = await p.runTitle(target, 'cold'); assert.equal(cold.success, true); assert.equal(cold.handshakes, 1); assert.equal(cold.segmentCount, 2);
    const warm = await p.runTitle(target, 'warm_repeat'); assert.equal(warm.success, true); assert.equal(warm.handshakes, 0);
    assert.deepEqual(warm.events.map(e => e.stage), ['master_manifest', 'video_segment', 'video_segment']);
    assert.equal(requests.filter(s => s === '/mobile/verify2.php').length, 1);
    assert.equal(JSON.stringify([cold, warm]).includes('SECRET'), false);
    assert.equal(JSON.parse(await readFile(join(dir, 'session.json'), 'utf8')).tHashTEncoded, 'SECRET_SESSION');
  } finally { await new Promise(r => server.close(r)); }
});
