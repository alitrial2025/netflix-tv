#!/usr/bin/env node
/**
 * HS approach 2: Known content IDs + JioCinema API + brute exploration
 * Hotstar.com is SPA — can't scrape. Need other approaches.
 */
import https from 'https';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept':'application/json,text/html,*/*','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        req(res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location, opts)
          .then(resolve).catch(reject); res.resume(); return;
      }
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end();
  });
}

const BASE = 'https://net52.cc';

console.log('╔══════════════════════════════════════════════╗');
console.log('║  HS APPROACH 2 — KNOWN IDS + API HUNTING    ║');
console.log('╚══════════════════════════════════════════════╝\n');

// ═══ 1. Test known Hotstar content IDs on net52 ═══
// These are real Hotstar/JioCinema IDs from public sources
console.log('═══ 1. KNOWN HOTSTAR IDs ON NET52 ═══\n');

const knownHsIds = [
  // Hotstar format (10 digits starting with 1)
  {id: '1260050764', name: 'Criminal Justice'},
  {id: '1260009879', name: 'Special Ops'},
  {id: '1260009597', name: 'Game of Thrones (Hotstar)'},
  {id: '1260019058', name: 'Aarya'},
  {id: '1770000008', name: 'Dil Bechara'},
  {id: '1000272071', name: 'Lakshmi'},
  
  // JioCinema numeric IDs (shorter)
  {id: '3698', name: 'GOT (JioCinema)'},
  {id: '3649', name: 'House of Dragon (JioCinema)'},
  {id: '3559', name: 'Succession (JioCinema)'},
  
  // Try alphanumeric like HBO Max catalog IDs
  {id: 'GYLXMi6E6yz4H3wEAAAAD', name: 'GOT (HBO)'},
  {id: 'GX4Z6MESr8pQCwgEAAAAe', name: 'Succession (HBO)'},
];

for (const show of knownHsIds) {
  const epR = await req(BASE + '/mobile/hs/episodes.php?s=' + show.id + '&p=0');
  let result = '';
  try {
    const p = JSON.parse(epR.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      result = '✅ ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"';
    } else if (p?.nextPageSeason) {
      result = '⚠️ valid but empty (nextPageSeason=' + p.nextPageSeason + ')';
    } else if (p?.error) {
      result = '❌ ' + p.error;
    } else {
      result = '? ' + epR.body.substring(0,60);
    }
  } catch {
    result = '⚠️ ' + epR.body.substring(0,60).replace(/\n/g,' ');
  }
  console.log(show.name + ' (' + show.id + '): ' + result);
}

// ═══ 2. Also test playlist for HS IDs that responded ═══
console.log('\n═══ 2. HS PLAYLIST TEST ═══\n');

for (const show of knownHsIds) {
  const plR = await req(BASE + '/mobile/hs/playlist.php?id=' + show.id);
  try {
    const pl = JSON.parse(plR.body);
    const file = pl[0]?.sources?.[0]?.file;
    if (file && file.includes('hls')) {
      console.log(show.name + ': ✅ HLS -> ' + file.substring(0,80));
    } else if (pl[0]?.image2) {
      console.log(show.name + ': poster=' + pl[0].image2.substring(0,60));
    }
  } catch {}
}

// ═══ 3. Try JioCinema API endpoints ═══
console.log('\n═══ 3. JIOCINEMA API ═══\n');

const jioApis = [
  'https://apis.jiocinema.com/v3.1/content/search?q=game+of+thrones&type=show',
  'https://content-jiovoot.voot.com/psapi/media/voot/v1/search?q=game+of+thrones&type=SHOW',
  'https://psapi.voot.com/jio/voot/v1/search?q=succession&rows=5',
  'https://www.jiocinema.com/api/v1/search?q=game+of+thrones',
];

for (const url of jioApis) {
  const r = await req(url);
  console.log(url.substring(0,70) + ': ' + r.status + ' | ' + r.body.substring(0,120).replace(/\n/g,' '));
}

// ═══ 4. Try the main search endpoint with correct format ═══
console.log('\n═══ 4. HS SEARCH — DIFFERENT URL PATTERNS ═══\n');

const searchPatterns = [
  // Maybe HS search needs POST
  '/mobile/hs/search.php',
  // Maybe search is at a different path
  '/hs/search.php?s=succession',
  '/mobile/search.php?s=succession&ott=hs',
  // Maybe ADSearch finds HS content
  '/mobile/search.php?s=succession&ADSearch=true',
];

for (const path of searchPatterns) {
  const r = await req(BASE + path + (path.includes('?') ? '' : '?s=succession'));
  console.log(path + ': ' + r.status + ' | ' + r.body.substring(0,100).replace(/\n/g,' '));
}

// ═══ 5. Explore HS page structure with nf-custom.js hints ═══
console.log('\n═══ 5. DATA ATTRIBUTES ON HS BROWSE PAGE ═══\n');

// The nf-custom.js shows poster clicks use data-post attribute
// When browsing HS content, posters have data-post with HS IDs
// Try to get the full HS page with ott cookie
const hsPage = await req(BASE + '/mobile/series?app=1', {
  headers: {'Cookie': 'ott=hs', 'User-Agent': 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0'}
});
console.log('HS series page (ott cookie only): ' + hsPage.status + ' | ' + hsPage.body.length + 'B');

// Check if data-post values are present
const dataPostRe = /data-post="([^"]+)"/g;
let m;
const hsPostIds = new Set();
while ((m = dataPostRe.exec(hsPage.body)) !== null) hsPostIds.add(m[1]);
console.log('data-post IDs: ' + hsPostIds.size);
if (hsPostIds.size > 0) {
  console.log('Sample: ' + [...hsPostIds].slice(0,10).join(', '));
  
  // Test these IDs!
  console.log('\nTesting first 5 data-post IDs:');
  for (const id of [...hsPostIds].slice(0,5)) {
    const epR = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
    try {
      const p = JSON.parse(epR.body);
      const eps = p?.episodes || [];
      if (eps.length > 0) {
        console.log('  ✅ id=' + id + ': ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"');
      } else {
        console.log('  id=' + id + ': ' + (p?.error || 'empty'));
      }
    } catch {
      console.log('  id=' + id + ': ' + epR.body.substring(0,60).replace(/\n/g,' '));
    }
  }
}

// Also try imgcdn with these IDs
if (hsPostIds.size > 0) {
  console.log('\nTesting poster images:');
  for (const id of [...hsPostIds].slice(0,3)) {
    for (const prefix of ['poster', 'hs', 'pv', 'nf']) {
      const r = await req('https://imgcdn.kim/' + prefix + '/341/' + id + '.jpg');
      if (r.status === 200 && r.body.length > 500) {
        console.log('  ✅ imgcdn.kim/' + prefix + '/341/' + id + '.jpg: ' + r.body.length + 'B');
      }
    }
  }
}
