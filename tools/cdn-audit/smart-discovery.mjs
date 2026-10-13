#!/usr/bin/env node
/**
 * Final approach: Use TMDB API to get season/episode structure,
 * then derive net52 content IDs by probing episodes.php in a smart way.
 * 
 * TMDB is free, no auth needed for basic lookups, and gives us
 * the exact season count + episode count per season.
 * 
 * Then for each season, we call net52's /search.php with episode titles
 * OR probe episodes.php with pages to find content IDs.
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = https;
    const r = mod.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0', 'Accept':'application/json', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    r.end();
  });
}

const BASE = 'https://net52.cc';
const showId = '70155584';

console.log('=== SMART EPISODE DISCOVERY (no cookies) ===\n');

// Step 1: We know episodes.php works with pagination
// net52 episodes.php returns nextPage and nextPageSeason
// Let's try page navigation to discover seasons
console.log('--- Step 1: episodes.php pagination exploration ---');

// First call with show ID to see what nextPageSeason gives us
let r = await req(BASE + '/mobile/episodes.php?s=' + showId + '&p=0', {headers:{'X-Requested-With':'XMLHttpRequest'}});
let parsed;
try { parsed = JSON.parse(r.body); } catch { parsed = null; }
console.log('episodes.php s=showId p=0:', JSON.stringify(parsed, null, 2));

// Try p=1, p=2 etc with the show ID
for (let p = 1; p <= 5; p++) {
  r = await req(BASE + '/mobile/episodes.php?s=' + showId + '&p=' + p, {headers:{'X-Requested-With':'XMLHttpRequest'}});
  try { parsed = JSON.parse(r.body); } catch { parsed = null; }
  const eps = parsed?.episodes || [];
  if (eps.length > 0 || parsed?.nextPageSeason) {
    console.log('p=' + p + ':', eps.length + ' eps, nextPageSeason=' + parsed?.nextPageSeason + ', nextPage=' + parsed?.nextPage);
    if (eps.length > 0) {
      console.log('  First ep:', eps[0].id, eps[0].s, 'E' + eps[0].ep, eps[0].t);
    }
  }
}

// Step 2: Now try /episodes.php (non-mobile) — it returned 2475B before
// Let's explore its pagination
console.log('\n--- Step 2: /episodes.php (non-mobile) pagination ---');
r = await req(BASE + '/episodes.php?s=' + showId + '&p=0', {headers:{'X-Requested-With':'XMLHttpRequest'}});
try { parsed = JSON.parse(r.body); } catch { parsed = null; }
console.log('non-mobile s=showId p=0:', JSON.stringify(parsed, null, 2).substring(0, 500));

// Try with known S4 ID and explore pages
const knownS4 = '70037632';
console.log('\n--- Step 3: Known S4 ID episodes + pagination ---');
for (let p = 0; p <= 3; p++) {
  r = await req(BASE + '/episodes.php?s=' + knownS4 + '&p=' + p, {headers:{'X-Requested-With':'XMLHttpRequest'}});
  try { parsed = JSON.parse(r.body); } catch { parsed = null; }
  const eps = parsed?.episodes || [];
  const nextSeason = parsed?.nextPageSeason;
  const nextPage = parsed?.nextPage;
  console.log('S4 p=' + p + ':', eps.length + ' eps, nextPageSeason=' + nextSeason + ', nextPage=' + nextPage);
  if (eps.length > 0) {
    for (const ep of eps) console.log('  id=' + ep.id + ' ' + ep.s + 'E' + ep.ep + ' "' + (ep.t || '').substring(0, 40) + '"');
  }
  // If nextPageSeason is different, it's the NEXT season's ID!
  if (nextSeason && nextSeason !== knownS4) {
    console.log('  >>> NEXT SEASON ID DISCOVERED: ' + nextSeason + ' <<<');
  }
}

// Step 4: Chain through seasons using nextPageSeason!
console.log('\n--- Step 4: Chain through ALL seasons via nextPageSeason ---');
// Start from S4 and go forward
let currentSeasonId = knownS4;
const seasons = new Map();

// First go forward
for (let attempt = 0; attempt < 20; attempt++) {
  r = await req(BASE + '/episodes.php?s=' + currentSeasonId + '&p=0', {headers:{'X-Requested-With':'XMLHttpRequest'}});
  try { parsed = JSON.parse(r.body); } catch { break; }
  const eps = parsed?.episodes || [];
  if (eps.length === 0) break;
  
  const seasonTag = eps[0]?.s || '';
  const seasonNum = parseInt(seasonTag.replace(/\D/g, '')) || 0;
  
  if (seasonNum > 0 && !seasons.has(seasonNum)) {
    // Get ALL episodes for this season (paginate)
    const allEps = [...eps];
    let nextP = parsed?.nextPage;
    let nextS = parsed?.nextPageSeason;
    
    // If there are more pages for THIS season
    while (nextP && nextS === currentSeasonId) {
      const r2 = await req(BASE + '/episodes.php?s=' + currentSeasonId + '&p=' + nextP, {headers:{'X-Requested-With':'XMLHttpRequest'}});
      let p2; try { p2 = JSON.parse(r2.body); } catch { break; }
      const moreEps = p2?.episodes || [];
      allEps.push(...moreEps);
      nextP = p2?.nextPage;
      nextS = p2?.nextPageSeason;
    }
    
    seasons.set(seasonNum, { id: currentSeasonId, epCount: allEps.length, episodes: allEps });
    console.log('Season ' + seasonNum + ': ID=' + currentSeasonId + ' | ' + allEps.length + ' eps');
    
    // Move to next season
    if (nextS && nextS !== currentSeasonId) {
      currentSeasonId = nextS;
    } else {
      break;
    }
  } else {
    break;
  }
}

// Now go backward from S4
console.log('\nGoing backward...');
// We need to find S3, S2, S1. Try episodes.php pagination backwards
// Unfortunately there's no "previousPageSeason". But from the known S4 ID,
// we can check if the non-mobile version gives us more info.

// Try: Get all pages from the non-mobile endpoint with show ID
// The non-mobile episodes.php?s=showId showed nextPageSeason — maybe that chains
console.log('\n--- Step 5: Non-mobile episodes with showId pagination chain ---');
currentSeasonId = showId;
for (let p = 0; p <= 30; p++) {
  r = await req(BASE + '/episodes.php?s=' + currentSeasonId + '&p=' + p, {headers:{'X-Requested-With':'XMLHttpRequest'}});
  try { parsed = JSON.parse(r.body); } catch { break; }
  const eps = parsed?.episodes || [];
  const nextSeason = parsed?.nextPageSeason;
  const nextPage = parsed?.nextPage;
  
  if (eps.length > 0) {
    const s = eps[0]?.s || '?';
    const sNum = parseInt(s.replace(/\D/g, '')) || 0;
    if (sNum > 0 && !seasons.has(sNum)) {
      seasons.set(sNum, { id: currentSeasonId, epCount: eps.length, episodes: eps });
    }
    console.log('s=' + currentSeasonId + ' p=' + p + ': ' + eps.length + ' eps (' + s + ') nextS=' + nextSeason);
  }
  
  if (nextSeason && nextSeason !== currentSeasonId) {
    currentSeasonId = nextSeason;
    p = -1; // reset page for new season
  } else if (!nextPage || eps.length === 0) {
    break;
  }
}

// Final results
console.log('\n========================================');
console.log('FINAL: Found ' + seasons.size + ' seasons');
console.log('========================================');
const sorted = [...seasons.entries()].sort((a,b) => a[0] - b[0]);
for (const [num, data] of sorted) {
  console.log('Season ' + num + ': ID=' + data.id + ' | ' + data.epCount + ' episodes');
  for (const ep of data.episodes.slice(0, 3)) {
    console.log('  E' + ep.ep + ': id=' + ep.id + ' "' + (ep.t || '').substring(0, 50) + '"');
  }
  if (data.epCount > 3) console.log('  ...');
}
