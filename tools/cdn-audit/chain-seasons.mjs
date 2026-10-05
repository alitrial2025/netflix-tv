#!/usr/bin/env node
/**
 * Mobile episodes.php chain — check if nextPageSeason chains between seasons
 * AND test playlist.php with episode content IDs to get HLS directly
 */
import https from 'https';
import { URL } from 'url';

function req(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.get(url, {headers:{'User-Agent':'Mozilla/5.0','X-Requested-With':'XMLHttpRequest'},rejectUnauthorized:false,timeout:10000}, res => {
      let d=''; res.on('data',c=>d+=c); res.on('end',()=>resolve({status:res.statusCode,body:d}));
    }).on('error',reject);
  });
}

const BASE = 'https://net52.cc';
const knownS4 = '70037632';

console.log('=== MOBILE episodes.php CHAIN TEST ===\n');

// Test mobile episodes.php with multiple pages for S4
console.log('--- /mobile/episodes.php chain from S4 ---');
let currentSeason = knownS4;
const visited = new Set();
const seasons = new Map();

for (let round = 0; round < 15; round++) {
  if (visited.has(currentSeason)) {
    console.log('Already visited ' + currentSeason + ', stopping.');
    break;
  }
  visited.add(currentSeason);
  
  // Get page 0
  const r = await req(BASE + '/mobile/episodes.php?s=' + currentSeason + '&p=0');
  let parsed; try { parsed = JSON.parse(r.body); } catch { break; }
  const eps = parsed?.episodes || [];
  const nextPage = parsed?.nextPage;
  const nextPageSeason = parsed?.nextPageSeason;
  const nextPageShow = parsed?.nextPageShow;
  
  if (eps.length === 0) {
    console.log('s=' + currentSeason + ': empty');
    break;
  }
  
  const sTag = eps[0]?.s || '?';
  const sNum = parseInt(sTag.replace(/\D/g, '')) || 0;
  
  // Get ALL episodes (page through)
  let allEps = [...eps];
  let pg = 1;
  while (allEps.length < 30) { // safety
    const r2 = await req(BASE + '/mobile/episodes.php?s=' + currentSeason + '&p=' + pg);
    let p2; try { p2 = JSON.parse(r2.body); } catch { break; }
    const moreEps = p2?.episodes || [];
    if (moreEps.length === 0 || JSON.stringify(moreEps) === JSON.stringify(eps)) break;
    // Check if same season
    if (moreEps[0]?.s !== sTag) break;
    allEps.push(...moreEps);
    pg++;
    if (pg > 5) break;
  }
  
  seasons.set(sNum, { id: currentSeason, episodes: allEps });
  console.log('Season ' + sNum + ': ID=' + currentSeason + ' | ' + allEps.length + ' eps | nextPageSeason=' + nextPageSeason + ' | nextPageShow=' + nextPageShow + ' | nextPage=' + nextPage);
  for (const ep of allEps.slice(0,3)) {
    console.log('  E' + ep.ep + ': id=' + ep.id + ' "' + (ep.t||'').substring(0,40) + '"');
  }
  if (allEps.length > 3) console.log('  ... +' + (allEps.length-3) + ' more');
  
  // Try to chain
  if (nextPageSeason && nextPageSeason !== currentSeason) {
    console.log('  >>> Chaining to nextPageSeason: ' + nextPageSeason);
    currentSeason = nextPageSeason;
  } else {
    console.log('  No new nextPageSeason. Trying full JSON dump...');
    console.log('  Full response keys:', Object.keys(parsed).join(', '));
    console.log('  Full parsed:', JSON.stringify(parsed).substring(0, 300));
    break;
  }
}

// Also try going backwards by checking the full response structure
console.log('\n--- Testing non-mobile /episodes.php with showId for season list ---');
// The non-mobile returned nextPageSeason with showId — let's follow that chain properly
const showId = '70155584';
const r = await req(BASE + '/episodes.php?s=' + showId + '&p=0');
let parsed; try { parsed = JSON.parse(r.body); } catch { parsed = null; }
console.log('Full response:', JSON.stringify(parsed, null, 2).substring(0, 800));

// Follow nextPage on the non-mobile endpoint
if (parsed?.nextPage) {
  for (let p = 1; p <= 15; p++) {
    const r2 = await req(BASE + '/episodes.php?s=' + showId + '&p=' + p);
    let p2; try { p2 = JSON.parse(r2.body); } catch { break; }
    const eps = p2?.episodes || [];
    const ns = p2?.nextPageSeason;
    if (eps.length > 0) {
      const sTag = eps[0]?.s || '?';
      console.log('p=' + p + ': ' + eps.length + ' eps (' + sTag + ') nextPageSeason=' + ns);
      const sNum = parseInt(sTag.replace(/\D/g, '')) || 0;
      if (sNum > 0 && !seasons.has(sNum) && ns) {
        seasons.set(sNum, { id: ns, episodes: eps });
        console.log('  NEW SEASON FOUND! S' + sNum + ' ID=' + ns);
      }
    }
    if (ns && ns !== showId && !visited.has(ns)) {
      // Follow this season too
      const r3 = await req(BASE + '/episodes.php?s=' + ns + '&p=0');
      let p3; try { p3 = JSON.parse(r3.body); } catch { continue; }
      const eps3 = p3?.episodes || [];
      if (eps3.length > 0) {
        const sTag3 = eps3[0]?.s || '?';
        const sNum3 = parseInt(sTag3.replace(/\D/g, '')) || 0;
        if (sNum3 > 0 && !seasons.has(sNum3)) {
          seasons.set(sNum3, { id: ns, episodes: eps3 });
          console.log('  Followed S' + sNum3 + ' ID=' + ns + ' | ' + eps3.length + ' eps');
        }
      }
    }
    if (!eps.length) break;
  }
}

// Summary
console.log('\n========================================');
console.log('TOTAL SEASONS DISCOVERED: ' + seasons.size);
console.log('========================================');
const sorted = [...seasons.entries()].sort((a,b) => a[0] - b[0]);
for (const [num, data] of sorted) {
  console.log('S' + num + ': ID=' + data.id + ' | ' + data.episodes.length + ' eps');
}
