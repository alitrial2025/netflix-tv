#!/usr/bin/env node
/**
 * Test ALL OTT endpoints WITHOUT cookies
 * Using real IDs from MITM log
 */
import https from 'https';
import crypto from 'crypto';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end();
  });
}

const BASE = 'https://net52.cc';
const HASH1 = '235ca31540ab8d90fcef4a00de8a247c';

function buildToken(contentId, mode) {
  const ts = Math.floor(Date.now()/1000).toString();
  const h2 = crypto.createHash('md5').update(ts + contentId).digest('hex');
  return HASH1 + '::' + h2 + '::' + ts + '::' + mode;
}

console.log('╔══════════════════════════════════════════════════════╗');
console.log('║  ZERO-COOKIE TEST — ALL OTT ENDPOINTS               ║');
console.log('╚══════════════════════════════════════════════════════╝\n');

// ═══ PV (Prime Video) ═══
console.log('═══ PRIME VIDEO (/mobile/pv/) ═══\n');

// PV post.php - Lost (from MITM log)
const pvShowId = '0QBPFM3PCGAM8ZKSC7UZ16PBOE';
const pvEpId = '0H7HKT2M8ELZS4N6JR0AF1JID2';
const pvMovieId = '0ODQ3XX6G6CDN4G8I0BZZBYBNS';
const pvShow2 = '0UABA3VF0B9O4BUEJ395QE759W';

const ts = Math.floor(Date.now()/1000);

// post.php NO cookies
let r = await req(BASE + '/mobile/pv/post.php?id=' + pvShowId + '&t=' + ts);
console.log('post.php (Lost, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 150));
try {
  const p = JSON.parse(r.body);
  const eps = p?.episodes || [];
  if (eps.length > 0) {
    console.log('  ✅ ' + eps.length + ' episodes | S' + eps[0].s + 'E' + eps[0].ep + ' "' + eps[0].t + '" id=' + eps[0].id);
  } else if (p?.error) {
    console.log('  ❌ Error: ' + p.error);
  }
} catch {}

r = await req(BASE + '/mobile/pv/post.php?id=' + pvShow2 + '&t=' + ts);
console.log('post.php (show2, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 100));

r = await req(BASE + '/mobile/pv/post.php?id=' + pvMovieId + '&t=' + ts);
console.log('post.php (Arctic Convoy, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 100));

// playlist.php NO cookies
r = await req(BASE + '/mobile/pv/playlist.php?id=' + pvEpId + '&t=Lost&tm=' + ts);
console.log('\nplaylist.php (Lost S6E2, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 150));

// playlist.php for movie NO cookies
r = await req(BASE + '/mobile/pv/playlist.php?id=' + pvMovieId + '&t=Arctic&tm=' + ts);
console.log('playlist.php (movie, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 150));

// HLS NO cookies
const pvToken = buildToken(pvEpId, 'ek::m');
r = await req(BASE + '/mobile/pv/hls/' + pvEpId + '.m3u8?in=' + encodeURIComponent(pvToken));
console.log('\nhls (Lost S6E2, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 150));

// Also try eb mode (from MITM log)
const pvTokenEb = buildToken(pvEpId, 'eb::m');
r = await req(BASE + '/mobile/pv/hls/' + pvEpId + '.m3u8?in=' + encodeURIComponent(pvTokenEb));
console.log('hls (eb mode, NO cookie): ' + r.status + ' | ' + r.body.substring(0, 150));

// Search with ott=pv cookie only (no auth cookies)
r = await req(BASE + '/mobile/search.php?s=lost&t=' + ts, {headers:{'Cookie':'ott=pv'}});
console.log('\nsearch (ott=pv cookie only): ' + r.status + ' | ' + r.body.substring(0, 150));

// Non-mobile search
r = await req(BASE + '/search.php?s=lost');
console.log('search non-mobile: ' + r.status + ' | ' + r.body.substring(0, 150));


// ═══ DP (Disney+) ═══
console.log('\n\n═══ DISNEY+ (/mobile/dp/) ═══\n');

// Try dp endpoints with guessed paths
for (const ep of ['/mobile/dp/post.php', '/mobile/dp/playlist.php', '/mobile/dp/search.php']) {
  r = await req(BASE + ep + '?id=test&t=' + ts + '&s=loki');
  console.log(ep + ' (NO cookie): ' + r.status + ' | ' + r.body.substring(0, 80));
}

// ═══ HS (JioHotstar) ═══
console.log('\n\n═══ JIOHOTSTAR (/mobile/hs/) ═══\n');

for (const ep of ['/mobile/hs/post.php', '/mobile/hs/playlist.php', '/mobile/hs/search.php']) {
  r = await req(BASE + ep + '?id=test&t=' + ts + '&s=game+of+thrones');
  console.log(ep + ' (NO cookie): ' + r.status + ' | ' + r.body.substring(0, 80));
}

// ═══ PV poster images NO cookies ═══
console.log('\n\n═══ POSTER IMAGES (NO cookie) ═══\n');
const pvPosterIds = ['0FWYE5S7OMGRSNUMT92ADJAS0Y', '0O6ZA9LHCCLMFD248B9IE6UAYA', '0QBPFM3PCGAM8ZKSC7UZ16PBOE'];
for (const id of pvPosterIds) {
  r = await req('https://imgcdn.kim/pv/341/' + id + '.jpg');
  console.log('imgcdn.kim/pv/341/' + id + '.jpg: ' + r.status + ' | ' + r.body.length + 'B');
}

// NF poster for comparison
r = await req('https://imgcdn.kim/nf/341/81040344.jpg');
console.log('imgcdn.kim/nf/341/81040344.jpg (Squid Game): ' + r.status + ' | ' + r.body.length + 'B');

// Subtitles
console.log('\n═══ SUBTITLES (NO cookie) ═══\n');
r = await req('https://pv.subscdn.top/subs/' + pvEpId + '/en-us.[CC].srt');
console.log('pv.subscdn.top: ' + r.status + ' | ' + r.body.substring(0, 80));
