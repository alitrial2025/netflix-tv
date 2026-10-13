#!/usr/bin/env node
/**
 * FULL ZERO-COOKIE PIPELINE — PRIME VIDEO
 * Search → Episodes → Token → HLS → CDN → Segments
 */
import https from 'https';
import crypto from 'crypto';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end();
  });
}

const BASE = 'https://net52.cc';
const H1 = '235ca31540ab8d90fcef4a00de8a247c';
const t0 = Date.now();

console.log('╔══════════════════════════════════════════════════════╗');
console.log('║  PV ZERO-COOKIE PIPELINE — The Boys + Reacher      ║');
console.log('╚══════════════════════════════════════════════════════╝\n');

// ═══ Test multiple PV shows ═══
const shows = ['the boys', 'reacher', 'lost', 'jack ryan'];

for (const query of shows) {
  console.log('━━━ "' + query + '" ━━━');
  
  // A. Search
  const sR = await req(BASE + '/mobile/pv/search.php?s=' + encodeURIComponent(query));
  let showId, showTitle;
  try {
    const p = JSON.parse(sR.body);
    const items = p?.searchResult || [];
    if (items.length === 0) { console.log('  ❌ Not found\n'); continue; }
    showId = items[0].id;
    showTitle = items[0].t;
    console.log('  A. Search: id=' + showId + ' "' + showTitle + '"');
  } catch { console.log('  ❌ Search error\n'); continue; }
  
  // B. Episodes (get all seasons)
  const allEps = [];
  const seasonMap = new Map();
  let page = 0;
  let hasMore = true;
  while (hasMore && page < 20) {
    const eR = await req(BASE + '/mobile/pv/episodes.php?s=' + showId + '&p=' + page);
    try {
      const p = JSON.parse(eR.body);
      const eps = p?.episodes || [];
      if (eps.length === 0) break;
      for (const ep of eps) {
        allEps.push(ep);
        const sNum = ep.s || '?';
        if (!seasonMap.has(sNum)) seasonMap.set(sNum, []);
        seasonMap.get(sNum).push(ep);
      }
      hasMore = p?.nextPageShow === '1' || p?.nextPageShow === 1;
      page++;
    } catch { break; }
  }
  
  console.log('  B. Episodes: ' + allEps.length + ' total across ' + seasonMap.size + ' seasons');
  for (const [sNum, eps] of [...seasonMap.entries()].sort()) {
    console.log('     ' + sNum + ': ' + eps.length + ' eps | E1="' + eps[0].t + '" id=' + eps[0].id);
  }
  
  if (allEps.length === 0) { console.log(''); continue; }
  
  // C. Pick first episode, build token, test playback
  const testEp = allEps[0];
  const ts = Math.floor(Date.now()/1000).toString();
  const h2 = crypto.createHash('md5').update(ts + testEp.id).digest('hex');
  const token = H1 + '::' + h2 + '::' + ts + '::ek::m';
  
  // D. Playlist
  const plR = await req(BASE + '/mobile/pv/playlist.php?id=' + testEp.id + '&t=' + encodeURIComponent(showTitle) + '&tm=' + ts);
  let hlsUrl = '';
  try {
    const pl = JSON.parse(plR.body);
    hlsUrl = pl[0]?.sources?.[0]?.file || '';
    console.log('  C. Playlist: ' + (hlsUrl ? '✅ HLS URL found' : '❌ no URL'));
  } catch { console.log('  C. Playlist: parse error'); }
  
  // E. HLS master
  if (hlsUrl) {
    if (!hlsUrl.startsWith('http')) hlsUrl = BASE + hlsUrl;
    const hlsR = await req(hlsUrl);
    const hasCdn = hlsR.body.includes('nm-cdn');
    const audioTracks = (hlsR.body.match(/LANGUAGE="[^"]+"/g) || []).map(l => l.match(/"([^"]+)"/)[1]);
    console.log('  D. HLS: ' + hlsR.body.length + 'B | CDN=' + hasCdn + ' | Audio: ' + (audioTracks.join(', ') || 'none'));
    
    if (hasCdn) {
      // Extract CDN URL
      const cdnMatch = hlsR.body.match(/https?:\/\/[^\s"]+\.m3u8[^\s"]*/);
      if (cdnMatch) {
        const cdnR = await req(cdnMatch[0]);
        const segs = cdnR.body.split('\n').filter(l => l.trim() && !l.startsWith('#'));
        console.log('  E. CDN: ' + segs.length + ' segments | ' + cdnR.body.length + 'B');
      }
    }
  }
  
  console.log('');
}

// ═══ Also test HS (JioHotstar) ═══
console.log('\n╔══════════════════════════════════════════════════════╗');
console.log('║  HS ZERO-COOKIE — JIOHOTSTAR                        ║');
console.log('╚══════════════════════════════════════════════════════╝\n');

// HS search returns "Enter Some Words" - maybe needs different params
// Try HS episodes with different ID formats
const hsEndpoints = [
  '/mobile/hs/search.php?s=game+of+thrones&t=' + Math.floor(Date.now()/1000),
  '/mobile/hs/episodes.php?s=1260050764&p=0',
  '/mobile/hs/episodes.php?s=1260009879&p=0',
  // Try string IDs
  '/mobile/hs/episodes.php?s=game-of-thrones&p=0',
];

for (const ep of hsEndpoints) {
  const r = await req(BASE + ep);
  console.log(ep.substring(0,65) + ': ' + r.body.substring(0,120).replace(/\n/g,' '));
}

console.log('\nTotal time: ' + ((Date.now()-t0)/1000).toFixed(1) + 's');
