#!/usr/bin/env node
/**
 * Test admin endpoints on net52.cc — these are exposed without auth
 * and might give us season/episode data without cookies!
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET', 
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/133.0 Mobile Safari/537.36', 'X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const showId = '70155584'; // Smallville
const BASE = 'https://net52.cc';

console.log('=== ADMIN ENDPOINT DISCOVERY (no cookies) ===\n');

// 1. /admin/series.php?nfid=70155584 (the form action from the HTML)
console.log('--- 1. /admin/series.php?nfid=showId ---');
try {
  const r = await req(BASE + '/admin/series.php?nfid=' + showId);
  console.log('Status:', r.status, '| Size:', r.body.length + 'B');
  
  // Look for season IDs in the response
  const seasonIdPattern = /data-season_id=["']([^"']+)["']/g;
  const seasonNumPattern = /data-season_num=["']([^"']+)["']/g;
  const epIdPattern = /data-ep_id=["']([^"']+)["']/g;
  
  const seasonIds = [];
  let m;
  while ((m = seasonIdPattern.exec(r.body)) !== null) seasonIds.push(m[1]);
  
  const seasonNums = [];
  const snp = /data-season_num=["']([^"']+)["']/g;
  while ((m = snp.exec(r.body)) !== null) seasonNums.push(m[1]);
  
  const epIds = [];
  const eip = /data-ep_id=["']([^"']+)["']/g;
  while ((m = eip.exec(r.body)) !== null) epIds.push(m[1]);
  
  console.log('Season IDs found:', seasonIds.length, '| unique:', [...new Set(seasonIds)].length);
  console.log('Season nums found:', seasonNums.length);
  console.log('Episode IDs found:', epIds.length);
  
  if (seasonIds.length > 0) {
    // Map season nums to IDs
    const seasonMap = new Map();
    for (let i = 0; i < Math.min(seasonIds.length, seasonNums.length); i++) {
      if (!seasonMap.has(seasonNums[i])) {
        seasonMap.set(seasonNums[i], seasonIds[i]);
      }
    }
    console.log('\nSeason mapping:');
    const sorted = [...seasonMap.entries()].sort((a,b) => parseInt(a[0]) - parseInt(b[0]));
    for (const [num, id] of sorted) {
      console.log('  Season ' + num + ' -> ID ' + id);
    }
  }
  
  if (epIds.length > 0) {
    console.log('\nFirst 10 episode IDs:', epIds.slice(0,10).join(', '));
  }
  
  // Also look for data-series attributes
  const seriesPattern = /data-series=["']([^"']+)["']/g;
  const seriesIds = [];
  while ((m = seriesPattern.exec(r.body)) !== null) seriesIds.push(m[1]);
  if (seriesIds.length > 0) {
    console.log('Series IDs:', [...new Set(seriesIds)].join(', '));
  }
  
  // Save for inspection
  const { writeFile } = await import('fs/promises');
  await writeFile('tools/cdn-audit/admin-series.html', r.body);
  console.log('Saved to tools/cdn-audit/admin-series.html');
  
} catch(e) { console.log('Error:', e.message); }

// 2. /series.php?nfid=showId (without /admin/)
console.log('\n--- 2. /series.php?nfid=showId ---');
try {
  const r = await req(BASE + '/series.php?nfid=' + showId);
  console.log('Status:', r.status, '| Size:', r.body.length + 'B');
  const seasonIdPattern = /data-season_id=["']([^"']+)["']/g;
  const ids = [];
  let m2;
  while ((m2 = seasonIdPattern.exec(r.body)) !== null) ids.push(m2[1]);
  console.log('Season IDs:', ids.length, ids.slice(0,5).join(', '));
} catch(e) { console.log('Error:', e.message); }

// 3. /admin/series.php?id=showId (try 'id' param instead of 'nfid')
console.log('\n--- 3. /admin/series.php?id=showId ---');
try {
  const r = await req(BASE + '/admin/series.php?id=' + showId);
  console.log('Status:', r.status, '| Size:', r.body.length + 'B');
  if (r.body.length > 200) console.log('Preview:', r.body.substring(0,200));
} catch(e) { console.log('Error:', e.message); }

// 4. Try the /admin/imdb.php search
console.log('\n--- 4. /admin/imdb.php ---');
try {
  const r = await req(BASE + '/admin/imdb.php?s=smallville');
  console.log('Status:', r.status, '| Size:', r.body.length + 'B');
  console.log('Body:', r.body.substring(0,300));
} catch(e) { console.log('Error:', e.message); }

console.log('\n=== DONE ===');
