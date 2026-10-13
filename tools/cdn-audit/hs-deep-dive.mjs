#!/usr/bin/env node
/**
 * JioHotstar deep dive:
 * 1. Extract content IDs from hotstar.com page (174KB)
 * 2. Find HS ID format from the poster URLs on the browse page
 * 3. Test HS endpoints with real hotstar IDs
 */
import https from 'https';
import { writeFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36',
        'Accept':'text/html,application/json,*/*','Accept-Language':'en-US,en;q=0.9', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
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
const ts = Math.floor(Date.now()/1000);

console.log('╔═════════════════════════════════════════════╗');
console.log('║  JIOHOTSTAR DEEP DIVE                      ║');
console.log('╚═════════════════════════════════════════════╝\n');

// ═══ 1. Scrape hotstar.com ═══
console.log('═══ 1. HOTSTAR.COM ═══\n');

const hotstarR = await req('https://www.hotstar.com/in/');
console.log('hotstar.com/in/: ' + hotstarR.status + ' | ' + hotstarR.body.length + 'B');
await writeFile('tools/cdn-audit/hotstar-page.html', hotstarR.body);

// Extract content IDs from the page
// Hotstar uses numeric IDs like 1260050764 (10-digit) or 1770003985
const numericIds = new Set();
const idRe = /\b1[0-9]{9}\b/g;
let m;
while ((m = idRe.exec(hotstarR.body)) !== null) numericIds.add(m[1] || m[0]);

console.log('10-digit IDs (1xxxxxxxxx): ' + numericIds.size);
if (numericIds.size > 0) console.log('Sample: ' + [...numericIds].slice(0,10).join(', '));

// Look for content URLs like /in/shows/show-name/1260050764
const urlRe = /\/in\/(?:shows?|movies?)\/([^/"]+)\/(\d{10})/g;
const contentUrls = [];
while ((m = urlRe.exec(hotstarR.body)) !== null) {
  contentUrls.push({ title: m[1], id: m[2] });
}
console.log('\nContent URLs: ' + contentUrls.length);
for (const c of contentUrls.slice(0,10)) {
  console.log('  ' + c.title + ' -> id=' + c.id);
}

// Also look for JSON data with contentId
const jsonIdRe = /(?:contentId|content_id|id)["':\s]+(\d{10})/g;
const jsonIds = new Set();
while ((m = jsonIdRe.exec(hotstarR.body)) !== null) jsonIds.add(m[1]);
console.log('\nJSON IDs: ' + jsonIds.size);
if (jsonIds.size > 0) console.log('Sample: ' + [...jsonIds].slice(0,5).join(', '));

// ═══ 2. Try specific Hotstar show pages ═══
console.log('\n═══ 2. HOTSTAR SHOW PAGES ═══\n');

// Known Hotstar shows - try to get their pages
const hotstarShows = [
  'https://www.hotstar.com/in/shows/criminal-justice/1260050764',
  'https://www.hotstar.com/in/shows/special-ops/1260009879',
  'https://www.hotstar.com/in/shows/game-of-thrones/1260009597',
  'https://www.jiocinema.com/tv-shows/game-of-thrones/3698',
];

for (const url of hotstarShows) {
  const r = await req(url);
  console.log(url.substring(url.indexOf('.com/')+4, url.indexOf('.com/')+50) + ': ' + r.status + ' | ' + r.body.length + 'B');
  
  if (r.body.length > 5000) {
    // Extract IDs
    const ids = new Set();
    const re = new RegExp(idRe.source, 'g');
    while ((m = re.exec(r.body)) !== null) ids.add(m[0]);
    console.log('  10-digit IDs: ' + ids.size);
    if (ids.size > 0) console.log('  Sample: ' + [...ids].slice(0,8).join(', '));
  }
}

// ═══ 3. Test HS endpoints with discovered IDs ═══
console.log('\n═══ 3. TEST HS ENDPOINTS ═══\n');

// Combine all discovered hotstar IDs
const testIds = [...numericIds, ...contentUrls.map(c => c.id), ...(jsonIds)].slice(0,15);
const uniqueIds = [...new Set(testIds)];

console.log('Testing ' + uniqueIds.length + ' IDs against net52 HS endpoints:\n');

for (const id of uniqueIds.slice(0,10)) {
  // episodes.php
  const epR = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0', {
    headers: {'X-Requested-With':'XMLHttpRequest'}
  });
  try {
    const p = JSON.parse(epR.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      console.log('✅ episodes id=' + id + ': ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"');
    } else if (p?.nextPageSeason) {
      console.log('⚠️ episodes id=' + id + ': empty but nextPageSeason=' + p.nextPageSeason);
    } else if (p?.error) {
      console.log('❌ episodes id=' + id + ': ' + p.error);
    } else {
      console.log('? episodes id=' + id + ': ' + epR.body.substring(0,80));
    }
  } catch {
    console.log('? episodes id=' + id + ': ' + epR.body.substring(0,60).replace(/\n/g,' '));
  }
  
  // playlist.php
  const plR = await req(BASE + '/mobile/hs/playlist.php?id=' + id, {
    headers: {'X-Requested-With':'XMLHttpRequest'}
  });
  try {
    const pl = JSON.parse(plR.body);
    const hasFile = pl[0]?.sources?.[0]?.file;
    if (hasFile) {
      console.log('  playlist: ✅ HLS URL found -> ' + hasFile.substring(0,80));
    } else {
      console.log('  playlist: ' + plR.body.substring(0,60));
    }
  } catch {
    console.log('  playlist: ' + plR.body.substring(0,60).replace(/\n/g,' '));
  }
}

// ═══ 4. Check HS poster on imgcdn ═══
console.log('\n═══ 4. HS POSTERS ON IMGCDN ═══\n');

// Try with "poster" path like the default
for (const id of uniqueIds.slice(0,3)) {
  for (const path of ['poster', 'hs', 'hotstar', 'jc']) {
    const r = await req('https://imgcdn.kim/' + path + '/341/' + id + '.jpg');
    if (r.status === 200 && r.body.length > 500) {
      console.log('✅ imgcdn.kim/' + path + '/341/' + id + '.jpg: ' + r.body.length + 'B');
    }
  }
}
