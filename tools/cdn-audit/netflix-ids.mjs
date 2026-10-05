#!/usr/bin/env node
/**
 * Get season/episode Netflix IDs from Netflix's OWN public data
 * and from TMDB (which stores Netflix IDs in external_ids).
 * 
 * Since net52 uses REAL Netflix IDs, we can get the complete 
 * season/episode structure from Netflix directly — no cookies needed.
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36', 'Accept':'*/*', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      // Follow redirects
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://' + u.hostname + res.headers.location;
        req(loc, opts).then(resolve).catch(reject);
        res.resume();
        return;
      }
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    r.end();
  });
}

const showNfId = '70155584'; // Smallville
console.log('=== NETFLIX ID RESOLUTION — Get Season IDs from Source ===');
console.log('Show:', showNfId, '(Smallville)\n');

// ═══════════════════════════════════════════════════
// APPROACH 1: Netflix's public title page metadata
// ═══════════════════════════════════════════════════
console.log('--- Approach 1: Netflix title page ---');
try {
  const r = await req('https://www.netflix.com/title/' + showNfId, {
    headers: {'Accept': 'text/html', 'Accept-Language': 'en-US'}
  });
  console.log('HTTP', r.status, '|', r.body.length + 'B');
  
  // Netflix embeds JSON-LD or React state with season data
  const ldMatch = r.body.match(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/);
  if (ldMatch) {
    try {
      const ld = JSON.parse(ldMatch[1]);
      console.log('JSON-LD found:', JSON.stringify(ld).substring(0, 500));
    } catch {}
  }
  
  // Look for __NEXT_DATA__ or reactContext
  const nextDataMatch = r.body.match(/__NEXT_DATA__\s*=\s*(\{[\s\S]*?\});?\s*<\/script>/);
  if (nextDataMatch) {
    console.log('__NEXT_DATA__ found:', nextDataMatch[1].substring(0, 300));
  }
  
  // Look for season/episode IDs in the HTML
  const seasonPattern = /["']seasonId["']\s*:\s*["']?(\d+)["']?/g;
  const seasonIds = [];
  let m;
  while ((m = seasonPattern.exec(r.body)) !== null) seasonIds.push(m[1]);
  if (seasonIds.length) console.log('Season IDs:', [...new Set(seasonIds)].join(', '));
  
  // Look for any Netflix ID patterns
  const nfIdPattern = /["\s:,]([678]\d{7})["\s:,]/g;
  const nfIds = new Set();
  while ((m = nfIdPattern.exec(r.body)) !== null) nfIds.add(m[1]);
  if (nfIds.size) console.log('Netflix-like IDs found:', nfIds.size, '|', [...nfIds].slice(0,15).join(', '));
  
} catch(e) { console.log('Error:', e.message); }

// ═══════════════════════════════════════════════════
// APPROACH 2: Netflix Shakti API (metadata endpoint)
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 2: Netflix Shakti/Falkor API ---');
const shaktiPaths = [
  '/api/shakti/mre/metadata?movieid=' + showNfId,
  '/api/shakti/mre/pathEvaluator?path=["videos",' + showNfId + ',["seasons","episodes"]]',
  '/nq/website/memberapi/v../metadata?movieid=' + showNfId,
];
for (const p of shaktiPaths) {
  try {
    const r = await req('https://www.netflix.com' + p, {
      headers: {'Accept': 'application/json'}
    });
    console.log(p.substring(0,60) + ':', r.status, '|', r.body.length + 'B');
    if (r.body.length < 500) console.log('  body:', r.body.substring(0,200));
  } catch(e) { console.log(p.substring(0,60) + ':', e.message); }
}

// ═══════════════════════════════════════════════════
// APPROACH 3: TMDB API (free, has Netflix external IDs)
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 3: TMDB API ---');
// TMDB search for Smallville
try {
  const r = await req('https://api.themoviedb.org/3/search/tv?query=smallville&api_key=demo');
  console.log('TMDB search:', r.status, '|', r.body.length + 'B');
  if (r.body.length < 300) console.log('  body:', r.body);
} catch(e) { console.log('TMDB error:', e.message); }

// ═══════════════════════════════════════════════════
// APPROACH 4: UNoGS API (Netflix catalog database)
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 4: UNoGS (Netflix catalog DB) ---');
try {
  const r = await req('https://unogs.com/api/title/detail?netflixid=' + showNfId);
  console.log('UNoGS:', r.status, '|', r.body.length + 'B');
  if (r.body.length < 500) console.log('  body:', r.body.substring(0,300));
} catch(e) { console.log('UNoGS error:', e.message); }

// ═══════════════════════════════════════════════════
// APPROACH 5: Netflix's own GraphQL/Pathfinder API
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 5: Netflix GraphQL ---');
try {
  const query = {"operationName":"metadata","variables":{"id":parseInt(showNfId)},"query":"query metadata($id:Int!){video(id:$id){seasons{seasonId seasonNumber episodes{episodeId episodeNumber}}}}"};
  const r = await req('https://www.netflix.com/graphql', {
    method: 'POST',
    headers: {'Content-Type':'application/json','Accept':'application/json'},
  });
  console.log('GraphQL:', r.status, '|', r.body.length + 'B');
} catch(e) { console.log('GraphQL error:', e.message); }

// ═══════════════════════════════════════════════════
// APPROACH 6: Netflix's public ODATA feed / mobile API
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 6: Netflix mobile API ---');
const nfPaths = [
  '/api/mobile/v1/title/' + showNfId + '/seasons',
  '/browse/v1/title/' + showNfId,
  '/metadata/v1/title/' + showNfId,
  '/catalog/v1/title/' + showNfId,
];
for (const p of nfPaths) {
  try {
    const r = await req('https://www.netflix.com' + p, {
      headers: {'Accept': 'application/json'}
    });
    console.log(p + ':', r.status, '|', r.body.length + 'B');
  } catch(e) { console.log(p + ':', e.message); }
}

// ═══════════════════════════════════════════════════
// APPROACH 7: Netflix android API (the actual mobile endpoint)
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 7: Netflix Android/iOS API ---');
const androidPaths = [
  'https://api.netflix.com/catalog/v1/title/' + showNfId,
  'https://api-global.netflix.com/v1/title/' + showNfId,
  'https://android.prod.api.netflix.com/oc/catalog/v1/title/' + showNfId,
];
for (const p of androidPaths) {
  try {
    const r = await req(p, {headers: {'Accept': 'application/json'}});
    console.log(p.substring(0,60) + ':', r.status, '|', r.body.length + 'B');
  } catch(e) { console.log(p.substring(0,60) + ':', e.message); }
}

// ═══════════════════════════════════════════════════
// APPROACH 8: Validate with known S4 ID on episodes.php
// ═══════════════════════════════════════════════════
console.log('\n--- Approach 8: Cross-validate known Netflix season IDs ---');
// If we can find Netflix season IDs from ANY source above,
// verify they work on net52's episodes.php
const knownS4 = '70037632';
try {
  const r = await req('https://net52.cc/mobile/episodes.php?s=' + knownS4 + '&p=0', {
    headers: {'X-Requested-With':'XMLHttpRequest'}
  });
  const parsed = JSON.parse(r.body);
  const eps = parsed?.episodes || [];
  console.log('net52 S4 verify: ' + eps.length + ' eps, first: ' + (eps[0]?.t || 'none'));
} catch(e) { console.log('Verify error:', e.message); }

console.log('\n=== DONE ===');
