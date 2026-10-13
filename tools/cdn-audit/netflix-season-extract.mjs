#!/usr/bin/env node
/**
 * Extract season/episode IDs from Netflix's own title page.
 * The page contains 140+ Netflix IDs — including season IDs!
 * Then validate each one against net52's episodes.php.
 */
import https from 'https';
import { URL } from 'url';
import { writeFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
        req(loc, opts).then(resolve).catch(reject);
        res.resume();
        return;
      }
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    r.end();
  });
}

const showNfId = '70155584';
console.log('=== EXTRACT SEASON IDS FROM NETFLIX TITLE PAGE ===\n');

// Step 1: Fetch Netflix title page  
console.log('Fetching netflix.com/title/' + showNfId + '...');
const nf = await req('https://www.netflix.com/title/' + showNfId, {
  headers: {'Accept':'text/html','Accept-Language':'en-US'}
});
console.log('Got', nf.body.length, 'bytes');

// Step 2: Extract all 7-9 digit Netflix IDs
const idPattern = /\b([5-9]\d{7})\b/g;
const allIds = new Set();
let m;
while ((m = idPattern.exec(nf.body)) !== null) allIds.add(m[1]);
console.log('Unique Netflix-range IDs:', allIds.size);

// Step 3: Look for structured season data in JSON blocks
// Netflix embeds React state / Falkor data as JSON in script tags
const scriptPattern = /<script[^>]*>([\s\S]*?)<\/script>/g;
let seasonData = null;
while ((m = scriptPattern.exec(nf.body)) !== null) {
  const script = m[1];
  // Look for season-related structures
  if (script.includes('seasonId') || script.includes('season_id') || script.includes('"seasons"')) {
    console.log('\nFound script with season data (' + script.length + 'B)');
    
    // Try to extract season objects
    const seasonObjPattern = /"seasonId"\s*:\s*(\d+)/g;
    const sIds = [];
    let sm;
    while ((sm = seasonObjPattern.exec(script)) !== null) sIds.push(sm[1]);
    if (sIds.length) {
      console.log('seasonId values:', sIds.join(', '));
      seasonData = sIds;
    }
    
    // Also try season number + ID pairs
    const pairPattern = /"(?:season|s)(?:Num|Number|_num)"\s*:\s*(\d+)[\s\S]*?"(?:seasonId|season_id|id)"\s*:\s*(\d+)/g;
    while ((sm = pairPattern.exec(script)) !== null) {
      console.log('Season ' + sm[1] + ' -> ID ' + sm[2]);
    }
  }
}

// Step 4: Look for the Falkor/pathEvaluator cache that Netflix embeds
const falkorMatch = nf.body.match(/netflix\.reactContext\s*=\s*(\{[\s\S]*?\});\s*<\/script>/);
if (falkorMatch) {
  console.log('\nFound reactContext (' + falkorMatch[1].length + 'B)');
  try {
    // This might be too big to parse directly, search for season patterns
    const rc = falkorMatch[1];
    const seasonPattern = /(\d{8}).*?"seasons"/g;
    while ((m = seasonPattern.exec(rc)) !== null) {
      console.log('Season ref:', m[1]);
    }
  } catch {}
}

// Step 5: Check /browse endpoint 
console.log('\nFetching /browse/v1/title/' + showNfId + '...');
const browse = await req('https://www.netflix.com/browse/v1/title/' + showNfId, {
  headers: {'Accept':'text/html,application/json'}
});
console.log('Got', browse.body.length, 'bytes');

// Extract IDs from browse response too
const browseIds = new Set();
const browseIdPattern = /\b([5-9]\d{7})\b/g;
while ((m = browseIdPattern.exec(browse.body)) !== null) browseIds.add(m[1]);
console.log('IDs in browse:', browseIds.size);

// Step 6: Validate ALL found IDs against net52 episodes.php
console.log('\n=== VALIDATING IDs against net52 episodes.php ===');
const candidates = [...allIds, ...browseIds];
const uniqueCandidates = [...new Set(candidates)].sort();
console.log('Total candidate IDs:', uniqueCandidates.length);

const seasons = new Map();
const batchSize = 5;

for (let i = 0; i < uniqueCandidates.length; i += batchSize) {
  const batch = uniqueCandidates.slice(i, i + batchSize);
  const results = await Promise.all(batch.map(async id => {
    try {
      const r = await req('https://net52.cc/mobile/episodes.php?s=' + id + '&p=0', {
        headers: {'X-Requested-With':'XMLHttpRequest'}
      });
      const parsed = JSON.parse(r.body);
      const eps = parsed?.episodes || [];
      if (eps.length > 0) {
        const sTag = eps[0]?.s || '';
        const sNum = parseInt(sTag.replace(/\D/g, '')) || 0;
        return { id, seasonNum: sNum, epCount: eps.length, firstEp: eps[0].t || '', sTag };
      }
    } catch {}
    return null;
  }));
  
  for (const r of results) {
    if (r && r.seasonNum > 0) {
      // Check if this is Smallville (by checking known episode titles)
      if (!seasons.has(r.seasonNum) || seasons.get(r.seasonNum).epCount < r.epCount) {
        seasons.set(r.seasonNum, r);
        console.log('  FOUND S' + r.seasonNum + ': ID=' + r.id + ' | ' + r.epCount + ' eps | "' + r.firstEp.substring(0,30) + '"');
      }
    }
  }
}

console.log('\n========================================');
console.log('SEASONS DISCOVERED FROM NETFLIX PAGE: ' + seasons.size);
console.log('========================================');
const sorted = [...seasons.entries()].sort((a,b) => a[0] - b[0]);
for (const [num, data] of sorted) {
  console.log('  S' + num + ': ID=' + data.id + ' | ' + data.epCount + ' episodes | "' + data.firstEp + '"');
}

// Save the mapping
const mapping = {};
for (const [num, data] of sorted) {
  mapping['S' + num] = { seasonId: data.id, episodeCount: data.epCount };
}
await writeFile('tools/cdn-audit/season-map.json', JSON.stringify({showId: showNfId, title: 'Smallville', seasons: mapping}, null, 2));
console.log('\nSaved to tools/cdn-audit/season-map.json');
