#!/usr/bin/env node
/**
 * HS Season ID hunt:
 * - episodes.php recognizes show IDs but returns empty → need SEASON IDs
 * - Hotstar.com is SPA → can't scrape
 * - Try JioCinema's GraphQL/API for season data
 * - Try brute-probing adjacent IDs (sequential like Netflix?)
 * - Try poster IDs on imgcdn to find HS poster prefix
 */
import https from 'https';
import crypto from 'crypto';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const proto = https;
    proto.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept':'application/json,text/html,*/*','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end(opts.body||undefined);
  });
}

const BASE = 'https://net52.cc';

console.log('╔═════════════════════════════════════════════╗');
console.log('║  HS SEASON HUNT                             ║');
console.log('╚═════════════════════════════════════════════╝\n');

// ═══ 1. Sequential probing near known Hotstar show IDs ═══
// Netflix season IDs are sequential. Are Hotstar season IDs too?
// Criminal Justice show = 1260050764. Seasons might be nearby.
console.log('═══ 1. SEQUENTIAL PROBING ═══\n');

const showId = 1260050764; // Criminal Justice
console.log('Probing near Criminal Justice (' + showId + ')...');

// Try ±20 from the show ID
for (let offset = -5; offset <= 20; offset++) {
  const id = (showId + offset).toString();
  const r = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
  try {
    const p = JSON.parse(r.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      console.log('  ✅ id=' + id + ' (offset=' + offset + '): ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"');
    }
  } catch {}
}

// Try Special Ops (1260009879) 
const showId2 = 1260009879;
console.log('\nProbing near Special Ops (' + showId2 + ')...');
for (let offset = -5; offset <= 20; offset++) {
  const id = (showId2 + offset).toString();
  const r = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
  try {
    const p = JSON.parse(r.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      console.log('  ✅ id=' + id + ' (offset=' + offset + '): ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"');
    }
  } catch {}
}

// Game of Thrones (1260009597) — should have 8 seasons
const showId3 = 1260009597;
console.log('\nProbing near GOT (' + showId3 + ')...');
for (let offset = -5; offset <= 20; offset++) {
  const id = (showId3 + offset).toString();
  const r = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
  try {
    const p = JSON.parse(r.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      console.log('  ✅ id=' + id + ' (offset=' + offset + '): ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"');
    }
  } catch {}
}

// ═══ 2. Try JioCinema short IDs ═══
console.log('\n═══ 2. JIOCINEMA SHORT IDs ═══\n');

// GOT on JioCinema = 3698. Season IDs might be different numbers
// Probe a range
console.log('Probing JioCinema IDs 3600-3750...');
const hits = [];
for (let id = 3600; id <= 3750; id++) {
  const r = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
  try {
    const p = JSON.parse(r.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      const line = '  ✅ id=' + id + ': ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"';
      console.log(line);
      hits.push({id, eps: eps.length, s: eps[0].s, ep: eps[0].ep, title: eps[0].t});
    }
  } catch {}
}
console.log('Hits: ' + hits.length);

// ═══ 3. Try the HS HLS with proper token ═══
console.log('\n═══ 3. HS HLS WITH TOKEN ═══\n');

// Use a show ID that playlist.php accepted
const testId = '1260050764';
const H1 = '235ca31540ab8d90fcef4a00de8a247c';
const ts = Math.floor(Date.now()/1000).toString();
const h2 = crypto.createHash('md5').update(ts + testId).digest('hex');
const token = H1 + '::' + h2 + '::' + ts + '::ek::m';

const hlsR = await req(BASE + '/mobile/hs/hls/' + testId + '.m3u8?in=' + encodeURIComponent(token));
console.log('HLS (Criminal Justice): ' + hlsR.status + ' | ' + hlsR.body.length + 'B');
console.log(hlsR.body.substring(0, 300));

// Also try GOT
const gotId = '1260009597';
const h2g = crypto.createHash('md5').update(ts + gotId).digest('hex');
const tokenG = H1 + '::' + h2g + '::' + ts + '::ek::m';
const hlsG = await req(BASE + '/mobile/hs/hls/' + gotId + '.m3u8?in=' + encodeURIComponent(tokenG));
console.log('\nHLS (GOT): ' + hlsG.status + ' | ' + hlsG.body.length + 'B');
console.log(hlsG.body.substring(0, 300));

// Try with eb mode too
const tokenEb = H1 + '::' + h2g + '::' + ts + '::eb::m';
const hlsEb = await req(BASE + '/mobile/hs/hls/' + gotId + '.m3u8?in=' + encodeURIComponent(tokenEb));
console.log('\nHLS (GOT, eb): ' + hlsEb.status + ' | ' + hlsEb.body.length + 'B');
console.log(hlsEb.body.substring(0, 300));

// JioCinema ID
const jcId = '3698';
const h2j = crypto.createHash('md5').update(ts + jcId).digest('hex');
const tokenJ = H1 + '::' + h2j + '::' + ts + '::ek::m';
const hlsJ = await req(BASE + '/mobile/hs/hls/' + jcId + '.m3u8?in=' + encodeURIComponent(tokenJ));
console.log('\nHLS (JC GOT 3698): ' + hlsJ.status + ' | ' + hlsJ.body.length + 'B');
console.log(hlsJ.body.substring(0, 300));
