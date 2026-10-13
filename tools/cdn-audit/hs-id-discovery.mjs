#!/usr/bin/env node
/**
 * JioHotstar (HS) ID discovery
 * 
 * HS merged: HBO Max, Paramount, Peacock
 * Need to find valid HS content IDs to test zero-cookie chain
 * 
 * Strategy:
 * 1. Try hotstar.com / jiocinema.com to find content IDs
 * 2. Probe HS endpoints with discovered IDs
 * 3. Check HS poster format on imgcdn.kim
 */
import https from 'https';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36',
        'Accept':'text/html,application/json,*/*','Accept-Language':'en-US,en;q=0.9','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        console.log('  -> ' + (res.headers.location||'').substring(0,100));
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

console.log('╔══════════════════════════════════════════╗');
console.log('║  JIOHOTSTAR (HS) — ID DISCOVERY          ║');
console.log('╚══════════════════════════════════════════╝\n');

// ═══ 1. Find HS posters on imgcdn.kim ═══
console.log('═══ 1. HS POSTER FORMAT ON IMGCDN ═══\n');
const imgPrefixes = ['hs', 'hotstar', 'jio', 'jc', 'dp'];
for (const prefix of imgPrefixes) {
  const r = await req('https://imgcdn.kim/' + prefix + '/341/test.jpg');
  console.log('imgcdn.kim/' + prefix + '/: ' + r.status + ' | ' + r.body.length + 'B');
}

// ═══ 2. Try HS search with different params ═══
console.log('\n═══ 2. HS SEARCH VARIATIONS ═══\n');

// From nf-custom.js: search uses {s, t, ADSearch}
const searchVariations = [
  '/mobile/hs/search.php?s=game+of+thrones',
  '/mobile/hs/search.php?s=game+of+thrones&t=' + ts,
  '/mobile/hs/search.php?s=game+of+thrones&t=' + ts + '&ADSearch=true',
  '/mobile/hs/search.php?s=succession',
  '/mobile/hs/search.php?s=halo',
  '/mobile/hs/search.php?s=shogun',
  '/mobile/hs/search.php?s=bollywood',
  '/mobile/hs/search.php?s=kgf',
  '/mobile/hs/search.php?s=rrr',
  '/mobile/hs/search.php?s=pathaan',
  '/mobile/hs/search.php?s=criminal+justice',
  '/mobile/hs/search.php?s=special+ops',
  '/mobile/hs/search.php?s=aarya',
];

for (const url of searchVariations) {
  const r = await req(BASE + url);
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      console.log(url.split('?s=')[1].split('&')[0] + ' -> ' + items.length + ' | id=' + items[0].id + ' "' + items[0].t + '"');
    } else {
      console.log(url.split('?s=')[1].split('&')[0] + ' -> ' + (p?.error || 'empty'));
    }
  } catch { console.log(url.split('?s=')[1].split('&')[0] + ' -> not JSON (' + r.body.length + 'B)'); }
}

// ═══ 3. Try HS with ott cookie ═══
console.log('\n═══ 3. HS SEARCH WITH OTT COOKIE ═══\n');

const hsSearches = ['game of thrones', 'succession', 'kgf', 'rrr', 'pathaan', 'special ops', 'criminal justice'];
for (const term of hsSearches) {
  const r = await req(BASE + '/mobile/hs/search.php?s=' + encodeURIComponent(term) + '&t=' + ts, {
    headers: {'Cookie': 'ott=hs'}
  });
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      console.log('"' + term + '" -> ' + items.length + ' | id=' + items[0].id + ' "' + items[0].t + '"');
    } else {
      console.log('"' + term + '" -> ' + (p?.error || p?.head || 'empty'));
    }
  } catch { console.log('"' + term + '" -> not JSON'); }
}

// ═══ 4. Try JioHotstar / JioCinema websites ═══
console.log('\n═══ 4. JIOHOTSTAR WEBSITES ═══\n');

const hsSites = [
  'https://www.hotstar.com/in/',
  'https://www.jiocinema.com/',
  'https://api.hotstar.com/o/v1/search?q=game+of+thrones&size=5',
  'https://api.jiocinema.com/search?q=game+of+thrones',
];

for (const url of hsSites) {
  const r = await req(url);
  console.log(url.substring(0,60) + ': ' + r.status + ' | ' + r.body.length + 'B');
  if (r.body.length > 1000) {
    // Look for content IDs
    const numIds = r.body.match(/\b\d{10}\b/g);
    if (numIds) console.log('  10-digit IDs: ' + [...new Set(numIds)].slice(0,5).join(', '));
    
    const contentIdRe = /contentId['":\s]+["']?(\d+)/g;
    let m;
    while ((m = contentIdRe.exec(r.body)) !== null) {
      console.log('  contentId: ' + m[1]);
    }
  }
}

// ═══ 5. Try fetching HS browse page from net52 with cookies ═══
console.log('\n═══ 5. HS BROWSE FROM SAVED PAGES ═══\n');

// We have the 119KB series page from before — check if it has HS content
// when the OTT was set to NF. The HS posters might have different imgcdn paths
import { readFile } from 'fs/promises';
try {
  const html = await readFile('tools/cdn-audit/pagemobile_series.html', 'utf8');
  // Look for hs or hotstar image paths
  const hsImgRe = /imgcdn[^"']*(hs|hotstar|jio|dp)[^"']*/g;
  let m2;
  while ((m2 = hsImgRe.exec(html)) !== null) {
    console.log('  HS image: ' + m2[0].substring(0,100));
  }
  
  // Look for non-NF poster patterns
  const nonNfRe = /imgcdn\.kim\/(?!nf\/)([^/"]+)\/(\d+|\w{26})\/([^"]+)/g;
  while ((m2 = nonNfRe.exec(html)) !== null) {
    console.log('  Non-NF poster: ' + m2[0].substring(0,100));
  }
} catch {}
