#!/usr/bin/env node
/**
 * HS ZERO-COOKIE CHAIN TEST — Using real dumped IDs
 * Test: episodes.php → playlist.php → HLS → CDN
 */
import https from 'https';
import crypto from 'crypto';
import { readFile } from 'fs/promises';

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

const catalog = JSON.parse(await readFile('tools/cdn-audit/ott-catalog.json','utf8'));

console.log('╔══════════════════════════════════════════════════╗');
console.log('║  HS ZERO-COOKIE CHAIN — Real IDs from Dump       ║');
console.log('╚══════════════════════════════════════════════════╝\n');

// Pick HS shows with episodes
const hsShows = catalog.hs.shows.filter(s => s.episodeCount > 0);
console.log('HS shows with episodes: ' + hsShows.length + '\n');

// Test first 5 shows
for (const show of hsShows.slice(0, 8)) {
  const firstSeason = Object.keys(show.seasons)[0];
  const eps = show.seasons[firstSeason];
  if (!eps || !eps.length) continue;
  
  const epId = eps[0].id;
  const showId = show.id;
  
  console.log('━━━ Show=' + showId + ' ' + firstSeason + ' EpID=' + epId + ' ━━━');
  
  // 1. Test episodes.php with show ID (NO cookies)
  const epR = await req(BASE + '/mobile/hs/episodes.php?s=' + showId + '&p=0');
  try {
    const p = JSON.parse(epR.body);
    const episodes = p?.episodes || [];
    if (episodes.length > 0) {
      console.log('  episodes.php (showId): ✅ ' + episodes.length + ' eps | ' + episodes[0].s + episodes[0].ep + ' "' + episodes[0].t + '" id=' + episodes[0].id);
    } else {
      console.log('  episodes.php (showId): ❌ empty (nextPageSeason=' + (p?.nextPageSeason||'none') + ')');
    }
  } catch { console.log('  episodes.php: error'); }
  
  // 2. Test playlist.php with episode ID (NO cookies)
  const plR = await req(BASE + '/mobile/hs/playlist.php?id=' + epId);
  try {
    const pl = JSON.parse(plR.body);
    const file = pl[0]?.sources?.[0]?.file;
    const subs = pl[0]?.tracks?.length || 0;
    if (file) {
      console.log('  playlist.php (epId): ✅ HLS found | ' + subs + ' subs');
    } else {
      console.log('  playlist.php (epId): ❌ no file');
    }
  } catch { console.log('  playlist.php: error | ' + plR.body.substring(0,60)); }
  
  // 3. Test HLS with proper token (NO cookies)
  const ts = Math.floor(Date.now()/1000).toString();
  const h2 = crypto.createHash('md5').update(ts + epId).digest('hex');
  
  // Try both ek and eb modes
  for (const mode of ['ek::m', 'eb::m']) {
    const token = H1 + '::' + h2 + '::' + ts + '::' + mode;
    const hlsR = await req(BASE + '/mobile/hs/hls/' + epId + '.m3u8?in=' + encodeURIComponent(token));
    const hasCdn = hlsR.body.includes('cdn') || hlsR.body.includes('freecdn');
    const isWarning = hlsR.body.includes('220884');
    const isEmpty = hlsR.body.length <= 30;
    
    if (hasCdn && !isWarning) {
      console.log('  HLS (' + mode + '): ✅ CDN URL | ' + hlsR.body.length + 'B');
      // Extract CDN URL
      const cdnM = hlsR.body.match(/https?:\/\/[^\s"]+/);
      if (cdnM) console.log('    CDN: ' + cdnM[0].substring(0,80));
    } else if (isWarning) {
      console.log('  HLS (' + mode + '): ⚠️ Rate-limit warning video');
    } else if (isEmpty) {
      console.log('  HLS (' + mode + '): ❌ Empty (' + hlsR.body.length + 'B)');
    } else {
      console.log('  HLS (' + mode + '): ? ' + hlsR.body.substring(0,80));
    }
  }
  
  console.log('');
}

// Also test HS movies
console.log('\n═══ HS MOVIES ═══\n');
const hsMovies = catalog.hs.movies.filter(m => m.id && m.id !== '+post_id+');
for (const movie of hsMovies.slice(0, 5)) {
  console.log('Movie=' + movie.id);
  
  const plR = await req(BASE + '/mobile/hs/playlist.php?id=' + movie.id);
  try {
    const pl = JSON.parse(plR.body);
    const file = pl[0]?.sources?.[0]?.file;
    if (file) console.log('  playlist: ✅ HLS');
    else console.log('  playlist: ❌');
  } catch {}
  
  const ts = Math.floor(Date.now()/1000).toString();
  const h2 = crypto.createHash('md5').update(ts + movie.id).digest('hex');
  const token = H1 + '::' + h2 + '::' + ts + '::eb::m';
  const hlsR = await req(BASE + '/mobile/hs/hls/' + movie.id + '.m3u8?in=' + encodeURIComponent(token));
  const hasCdn = hlsR.body.includes('cdn') && !hlsR.body.includes('220884');
  console.log('  HLS (eb): ' + (hasCdn ? '✅ CDN' : hlsR.body.length + 'B') + ' ' + hlsR.body.substring(0,80));
}

// Summary
console.log('\n═══ CATALOG SUMMARY ═══');
console.log('HS: ' + hsShows.length + ' shows (' + catalog.hs.shows.reduce((s,x)=>s+x.episodeCount,0) + ' eps) + ' + hsMovies.length + ' movies');

// Count unique episode IDs
let hsEpIds = 0;
for (const s of catalog.hs.shows) {
  for (const eps of Object.values(s.seasons)) {
    hsEpIds += eps.length;
  }
}
console.log('Total HS episode IDs cached: ' + hsEpIds);
