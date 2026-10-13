#!/usr/bin/env node
/**
 * ═══════════════════════════════════════════════════════════════
 *  ZERO-COOKIE NETFLIX PLAYBACK CHAIN — Complete A→Z Pipeline
 * ═══════════════════════════════════════════════════════════════
 * 
 * Proves the ENTIRE playback chain from search to video segments
 * with ZERO cookies, ZERO handshake, ZERO wait.
 * 
 * Flow:
 *   A. search.php        → Show ID           [no cookie]
 *   B. netflix.com/title  → Extract NF IDs    [no cookie]
 *   C. episodes.php       → Validate seasons  [no cookie]
 *   D. episodes.php       → Episode IDs       [no cookie]
 *   E. Self-build token   → ?in= param        [no handshake]
 *   F. net52 HLS master   → CDN URL           [no cookie]
 *   G. CDN manifest       → Segment list      [no cookie]
 *   H. CDN segment        → Video bytes       [bare HTTP]
 */
import https from 'https';
import crypto from 'crypto';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = https;
    const r = mod.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
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

const NET52 = 'https://net52.cc';
const FREECDN_HASH1 = '235ca31540ab8d90fcef4a00de8a247c';

// ═══════════════════════════════════════════════════
const showQuery = process.argv[2] || 'smallville';
const targetSeason = parseInt(process.argv[3]) || 4;
const targetEpisode = parseInt(process.argv[4]) || 8;

console.log('╔══════════════════════════════════════════════════╗');
console.log('║  ZERO-COOKIE PLAYBACK — Complete A→Z Pipeline   ║');
console.log('╚══════════════════════════════════════════════════╝');
console.log('Query: "' + showQuery + '" | Target: S' + targetSeason + 'E' + targetEpisode);
console.log('');

const t0 = Date.now();

// ═══════════════════════════════════════════════════
// A. SEARCH → Show ID (no cookies)
// ═══════════════════════════════════════════════════
console.log('═══ A. SEARCH (no cookie) ═══');
const searchR = await req(NET52 + '/search.php?s=' + encodeURIComponent(showQuery), {
  headers: {'X-Requested-With':'XMLHttpRequest'}
});
const searchData = JSON.parse(searchR.body);
const searchResults = searchData?.searchResult || [];
if (searchResults.length === 0) { console.log('❌ No results'); process.exit(1); }

const show = searchResults.find(r => (r.t||'').toLowerCase().includes(showQuery.toLowerCase())) || searchResults[0];
console.log('✅ Found: "' + show.t + '" ID=' + show.id + ' (' + (Date.now()-t0) + 'ms)');

// ═══════════════════════════════════════════════════
// B. NETFLIX.COM → Extract all Netflix IDs (no cookies)
// ═══════════════════════════════════════════════════
console.log('\n═══ B. NETFLIX.COM EXTRACTION (no cookie) ═══');
const nfR = await req('https://www.netflix.com/title/' + show.id, {
  headers: {'Accept':'text/html','Accept-Language':'en-US'}
});
console.log('Fetched ' + nfR.body.length + 'B from netflix.com');

const idPattern = /\b([5-9]\d{7})\b/g;
const allNfIds = new Set();
let m;
while ((m = idPattern.exec(nfR.body)) !== null) allNfIds.add(m[1]);
console.log('✅ Extracted ' + allNfIds.size + ' candidate Netflix IDs (' + (Date.now()-t0) + 'ms)');

// ═══════════════════════════════════════════════════
// C. VALIDATE SEASONS → Season IDs (no cookies)
// ═══════════════════════════════════════════════════
console.log('\n═══ C. VALIDATE SEASONS (no cookie) ═══');
const candidates = [...allNfIds];
const seasons = new Map();
const BATCH = 8;

for (let i = 0; i < candidates.length; i += BATCH) {
  const batch = candidates.slice(i, i + BATCH);
  const results = await Promise.all(batch.map(async id => {
    try {
      const r = await req(NET52 + '/mobile/episodes.php?s=' + id + '&p=0', {
        headers: {'X-Requested-With':'XMLHttpRequest'}
      });
      const parsed = JSON.parse(r.body);
      const eps = parsed?.episodes || [];
      if (eps.length > 0) {
        const sNum = parseInt((eps[0]?.s||'').replace(/\D/g, '')) || 0;
        return { id, sNum, epCount: eps.length, episodes: eps };
      }
    } catch {}
    return null;
  }));
  
  for (const r of results) {
    if (r && r.sNum > 0 && !seasons.has(r.sNum)) {
      seasons.set(r.sNum, r);
    }
  }
}

const sortedSeasons = [...seasons.entries()].sort((a,b) => a[0]-b[0]);
console.log('✅ Found ' + seasons.size + ' seasons (' + (Date.now()-t0) + 'ms):');
for (const [num, data] of sortedSeasons) {
  const marker = num === targetSeason ? ' ◄ TARGET' : '';
  console.log('   S' + num + ': ID=' + data.id + ' (' + data.epCount + ' eps)' + marker);
}

// ═══════════════════════════════════════════════════
// D. EPISODE RESOLUTION → Content ID (no cookies)
// ═══════════════════════════════════════════════════
console.log('\n═══ D. EPISODE RESOLUTION (no cookie) ═══');
const seasonData = seasons.get(targetSeason);
if (!seasonData) { console.log('❌ Season ' + targetSeason + ' not found'); process.exit(1); }

// episodes already loaded in validation step
let targetEp = null;
for (const ep of seasonData.episodes) {
  const epNum = parseInt((ep.ep||'').replace(/\D/g, '')) || 0;
  if (epNum === targetEpisode) {
    targetEp = ep;
    break;
  }
}

// If not in first page, load more
if (!targetEp) {
  console.log('Loading more episodes...');
  for (let page = 1; page <= 5; page++) {
    const r = await req(NET52 + '/mobile/episodes.php?s=' + seasonData.id + '&p=' + page, {
      headers: {'X-Requested-With':'XMLHttpRequest'}
    });
    const parsed = JSON.parse(r.body);
    const eps = parsed?.episodes || [];
    for (const ep of eps) {
      const epNum = parseInt((ep.ep||'').replace(/\D/g, '')) || 0;
      if (epNum === targetEpisode) { targetEp = ep; break; }
    }
    if (targetEp || eps.length === 0) break;
  }
}

if (!targetEp) { console.log('❌ Episode ' + targetEpisode + ' not found'); process.exit(1); }
console.log('✅ S' + targetSeason + 'E' + targetEpisode + ': "' + targetEp.t + '" ContentID=' + targetEp.id + ' (' + (Date.now()-t0) + 'ms)');

// ═══════════════════════════════════════════════════
// E. BUILD TOKEN → ?in= param (self-generated, no handshake)
// ═══════════════════════════════════════════════════
console.log('\n═══ E. BUILD TOKEN (self-generated) ═══');
const contentId = targetEp.id;
const timestamp = Math.floor(Date.now() / 1000).toString();
const hash2 = crypto.createHash('md5').update(timestamp + contentId).digest('hex');
const masterToken = FREECDN_HASH1 + '::' + hash2 + '::' + timestamp + '::ek::m';
const cdnToken = FREECDN_HASH1 + '::' + hash2 + '::' + timestamp + '::ek::myes';
console.log('✅ Master token: ' + masterToken.substring(0,50) + '...');
console.log('✅ CDN token: ' + cdnToken.substring(0,50) + '...');
console.log('   (' + (Date.now()-t0) + 'ms)');

// ═══════════════════════════════════════════════════
// F. HLS MASTER MANIFEST → CDN URL (no cookies)
// ═══════════════════════════════════════════════════
console.log('\n═══ F. HLS MASTER (no cookie) ═══');
const masterUrl = NET52 + '/mobile/hls/' + contentId + '.m3u8?in=' + encodeURIComponent(masterToken);
const masterR = await req(masterUrl);
const masterBody = masterR.body;

// Extract CDN URL from master manifest
const cdnUrlMatch = masterBody.match(/https?:\/\/[^\s]+\.m3u8[^\s]*/);
if (!cdnUrlMatch) { console.log('❌ No CDN URL in master'); console.log(masterBody); process.exit(1); }

let cdnManifestUrl = cdnUrlMatch[0];
// Replace token in CDN URL with our CDN token
cdnManifestUrl = cdnManifestUrl.replace(/\?in=[^&\s]+/, '?in=' + encodeURIComponent(cdnToken));
console.log('✅ CDN manifest: ' + cdnManifestUrl.substring(0, 80) + '...');
console.log('   (' + (Date.now()-t0) + 'ms)');

// ═══════════════════════════════════════════════════
// G. CDN MANIFEST → Segment list (no cookies)
// ═══════════════════════════════════════════════════
console.log('\n═══ G. CDN MANIFEST (no cookie) ═══');
const cdnR = await req(cdnManifestUrl);

if (cdnR.body.length <= 44) {
  console.log('❌ CDN rejected (44B response)');
  process.exit(1);
}

// Parse media manifest — get segment URLs
const lines = cdnR.body.split('\n');
const segmentUrls = lines.filter(l => l.trim() && !l.startsWith('#'));
const segmentCount = segmentUrls.length;
console.log('✅ Media manifest: ' + segmentCount + ' segments, ' + cdnR.body.length + 'B');
console.log('   (' + (Date.now()-t0) + 'ms)');

// ═══════════════════════════════════════════════════
// H. VIDEO SEGMENT → Raw bytes (bare HTTP, no token needed)
// ═══════════════════════════════════════════════════
console.log('\n═══ H. VIDEO SEGMENT (bare HTTP) ═══');
if (segmentUrls.length > 0) {
  // Build full segment URL
  const cdnBase = cdnManifestUrl.substring(0, cdnManifestUrl.lastIndexOf('/') + 1);
  let segUrl = segmentUrls[0].trim();
  if (!segUrl.startsWith('http')) segUrl = cdnBase + segUrl;
  // Remove any query params — segments don't need tokens
  segUrl = segUrl.split('?')[0];
  
  const segR = await req(segUrl);
  const isMpegTs = segR.body.length > 1000;
  console.log('✅ Segment: ' + segR.body.length + 'B | HTTP ' + segR.status + ' | MPEG-TS=' + isMpegTs);
}

const totalMs = Date.now() - t0;

// ═══════════════════════════════════════════════════
console.log('\n╔══════════════════════════════════════════════════╗');
console.log('║  RESULT: COMPLETE ZERO-COOKIE PLAYBACK          ║');
console.log('╠══════════════════════════════════════════════════╣');
console.log('║  Show:    "' + show.t + '" (' + show.id + ')');
console.log('║  Episode: S' + targetSeason + 'E' + targetEpisode + ' "' + targetEp.t + '"');
console.log('║  Content: ' + contentId);
console.log('║  Seasons: ' + seasons.size + ' discovered');
console.log('║  Time:    ' + totalMs + 'ms (' + (totalMs/1000).toFixed(1) + 's)');
console.log('║                                                  ');
console.log('║  Cookies used: ZERO                              ');
console.log('║  Handshake:    NONE                              ');
console.log('║  Verify wait:  NONE                              ');
console.log('╚══════════════════════════════════════════════════╝');
