#!/usr/bin/env node
/**
 * Extract PV season IDs from primevideo.com (like Netflix.com trick)
 * Test with "The Boys" — multi-season show
 */
import https from 'https';
import { writeFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36',
        'Accept':'text/html,*/*','Accept-Language':'en-US,en;q=0.9', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
        req(loc, opts).then(resolve).catch(reject); res.resume(); return;
      }
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end();
  });
}

const BASE = 'https://net52.cc';

// PV ID pattern: starts with 0, 26 chars alphanumeric
const PV_ID_RE = /\b(0[A-Z0-9]{25})\b/g;

console.log('╔══════════════════════════════════════════════╗');
console.log('║  PV SEASON EXTRACTION FROM PRIMEVIDEO.COM   ║');
console.log('╚══════════════════════════════════════════════╝\n');

const shows = [
  { name: 'The Boys', pvId: '0NSPK8CXRVWRF9LXT1WGZ9MES0' },
  { name: 'Reacher', pvId: '0RTZ57DQ6PBHH29UN5JS7U7CW4' },
  { name: 'Jack Ryan', pvId: '0SOYHJT89KBDF7AF3T8D9XVDSH' },
];

for (const show of shows) {
  console.log('━━━ "' + show.name + '" (id=' + show.pvId + ') ━━━');
  
  // Fetch primevideo.com page
  const pvR = await req('https://www.primevideo.com/detail/' + show.pvId);
  console.log('  primevideo.com: ' + pvR.status + ' | ' + pvR.body.length + 'B');
  
  if (pvR.body.length > 5000) {
    // Extract ALL PV-style IDs
    const allIds = new Set();
    let m;
    const re = new RegExp(PV_ID_RE.source, 'g');
    while ((m = re.exec(pvR.body)) !== null) allIds.add(m[1]);
    console.log('  PV IDs found: ' + allIds.size);
    
    if (allIds.size > 1) {
      // Remove the show ID itself
      allIds.delete(show.pvId);
      console.log('  Candidates (excl show): ' + allIds.size);
      
      // Validate each against episodes.php
      const seasons = new Map();
      const candidates = [...allIds];
      const BATCH = 6;
      
      for (let i = 0; i < candidates.length; i += BATCH) {
        const batch = candidates.slice(i, i + BATCH);
        const results = await Promise.all(batch.map(async id => {
          const r = await req(BASE + '/mobile/pv/episodes.php?s=' + id + '&p=0', {
            headers: {'X-Requested-With':'XMLHttpRequest'}
          });
          try {
            const p = JSON.parse(r.body);
            const eps = p?.episodes || [];
            if (eps.length > 0) {
              return { id, s: eps[0].s, epCount: eps.length, firstEp: eps[0].t, firstEpId: eps[0].id };
            }
          } catch {}
          return null;
        }));
        
        for (const r of results.filter(Boolean)) {
          if (!seasons.has(r.s)) seasons.set(r.s, r);
        }
      }
      
      if (seasons.size > 0) {
        console.log('  ✅ SEASONS FOUND: ' + seasons.size);
        for (const [s, data] of [...seasons.entries()].sort()) {
          console.log('    ' + s + ': ID=' + data.id + ' (' + data.epCount + ' eps) E1="' + data.firstEp + '"');
        }
      } else {
        console.log('  ⚠️ No seasons validated — trying show ID directly');
        // The show ID itself might work for episodes
        const r = await req(BASE + '/mobile/pv/episodes.php?s=' + show.pvId + '&p=0', {
          headers: {'X-Requested-With':'XMLHttpRequest'}
        });
        try {
          const p = JSON.parse(r.body);
          const eps = p?.episodes || [];
          if (eps.length > 0) {
            console.log('    Show ID works! ' + eps.length + ' eps | ' + eps[0].s + ' E1="' + eps[0].t + '"');
          }
        } catch {}
      }
    }
    
    // Also look for ASIN patterns and season selectors in the HTML
    const asinRe = /\b(B0[A-Z0-9]{8})\b/g;
    const asins = new Set();
    while ((m = asinRe.exec(pvR.body)) !== null) asins.add(m[1]);
    if (asins.size > 0) console.log('  ASINs: ' + [...asins].slice(0,5).join(', '));
    
    // Look for season data in JSON
    const seasonJsonRe = /season[s]?[\s'":\[]+[\s\S]{0,500}?(?:id|titleId|asin)['":\s]+["']([^"']+)/gi;
    while ((m = seasonJsonRe.exec(pvR.body)) !== null) {
      console.log('  Season JSON: ' + m[0].substring(0,100));
    }
  }
  console.log('');
}

// Also test: does the show ID itself work as a "season" for single-season shows?
console.log('━━━ Direct show ID test ━━━');
for (const show of shows) {
  const r = await req(BASE + '/mobile/pv/episodes.php?s=' + show.pvId + '&p=0', {
    headers: {'X-Requested-With':'XMLHttpRequest'}
  });
  try {
    const p = JSON.parse(r.body);
    const eps = p?.episodes || [];
    console.log(show.name + ' (show ID as season): ' + eps.length + ' eps' + (eps.length > 0 ? ' | ' + eps[0].s + ' "' + eps[0].t + '"' : ''));
  } catch { console.log(show.name + ': error'); }
}
