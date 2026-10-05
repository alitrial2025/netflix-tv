#!/usr/bin/env node
/**
 * Creative approaches to get season/episode IDs WITHOUT cookies.
 * The goal: show ID (70155584) → episode content IDs WITHOUT post.php
 */
import https from 'https';
import { URL } from 'url';
import { createHash } from 'crypto';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET', 
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/133.0 Mobile Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const showId = '70155584';
const BASE = 'https://net52.cc';
const hdr = {'X-Requested-With':'XMLHttpRequest'};

console.log('=== CREATIVE SEASON/EPISODE DISCOVERY (no cookies) ===\n');

// APPROACH 1: Search for specific episode strings
console.log('--- Approach 1: Search for episode-specific terms ---');
const searches = [
  'smallville season 4',
  'smallville s4',
  'smallville s04e08',
  'smallville crusade',        // S4E1 title
  'smallville gone',           // S4E2 title  
  '70155584',                  // show ID as search term
  '82171151',                  // known episode content ID
];
for (const term of searches) {
  try {
    const r = await req(BASE + '/search.php?s=' + encodeURIComponent(term), {headers:hdr});
    let parsed; try { parsed = JSON.parse(r.body); } catch {}
    const items = parsed?.searchResult || [];
    const head = parsed?.head || '';
    console.log(`  "${term}" -> ${items.length} results (${head}) | ${r.body.length}B`);
    for (const item of items.slice(0,3)) {
      console.log(`    id=${item.id} t="${item.t}" ${item.s||''} ${item.ep||''}`);
    }
  } catch(e) { console.log(`  "${term}" -> ERROR: ${e.message}`); }
}

// APPROACH 2: playlist.php with show ID (not episode ID) — does it list episodes?
console.log('\n--- Approach 2: playlist.php with SHOW ID ---');
const ts = Math.floor(Date.now()/1000);
const hash2 = createHash('md5').update(ts.toString() + showId).digest('hex');
const token = `235ca31540ab8d90fcef4a00de8a247c::${hash2}::${ts}::ek::m`;
try {
  const r = await req(BASE + '/mobile/playlist.php?id=' + showId + '&tm=' + ts + '&in=' + token, {headers:hdr});
  console.log(`  playlist.php(showId): ${r.status} | ${r.body.length}B`);
  console.log(`  body: ${r.body.substring(0,400)}`);
} catch(e) { console.log(`  Error: ${e.message}`); }

// Also try non-mobile
try {
  const r = await req(BASE + '/playlist.php?id=' + showId + '&tm=' + ts + '&in=' + token, {headers:hdr});
  console.log(`  /playlist.php(showId): ${r.status} | ${r.body.length}B`);
  console.log(`  body: ${r.body.substring(0,400)}`);
} catch(e) { console.log(`  Error: ${e.message}`); }

// APPROACH 3: HLS with show ID — does it work or error differently?
console.log('\n--- Approach 3: HLS master with SHOW ID ---');
try {
  const r = await req(BASE + '/mobile/hls/' + showId + '.m3u8?in=' + token + '&hd=off&lang=eng&hp=yes', {headers:hdr});
  console.log(`  hls(showId): ${r.status} | ${r.body.length}B | HLS=${r.body.includes('#EXTM3U')}`);
  if (r.body.length < 500) console.log(`  body: ${r.body}`);
} catch(e) { console.log(`  Error: ${e.message}`); }

// APPROACH 4: episodes.php with SHOW ID as season param
console.log('\n--- Approach 4: episodes.php with show ID as season ---');
try {
  const r = await req(BASE + '/mobile/episodes.php?s=' + showId + '&p=0', {headers:hdr});
  let parsed; try { parsed = JSON.parse(r.body); } catch {}
  const eps = parsed?.episodes || [];
  console.log(`  episodes(showId as s): ${eps.length} eps | ${r.body.length}B`);
  if (eps.length > 0) {
    for (const ep of eps.slice(0,3)) console.log(`    id=${ep.id} s=${ep.s} ep=${ep.ep} t="${ep.t}"`);
  }
} catch(e) { console.log(`  Error: ${e.message}`); }

// Also try different page params
for (const p of ['1','2','3']) {
  try {
    const r = await req(BASE + '/mobile/episodes.php?s=' + showId + '&p=' + p, {headers:hdr});
    let parsed; try { parsed = JSON.parse(r.body); } catch {}
    const eps = parsed?.episodes || [];
    if (eps.length > 0) console.log(`  episodes(showId, p=${p}): ${eps.length} eps`);
  } catch {}
}

// APPROACH 5: Try /mobile/episodes.php with different param names
console.log('\n--- Approach 5: episodes.php with alternative params ---');
const paramTests = [
  `id=${showId}`,
  `show=${showId}`,
  `show_id=${showId}`,
  `series=${showId}`,
  `sid=${showId}`,
  `parent=${showId}`,
  `s=${showId}&season=4`,
  `id=${showId}&season=4`,
  `id=${showId}&s=4`,
];
for (const params of paramTests) {
  try {
    const r = await req(BASE + '/mobile/episodes.php?' + params, {headers:hdr});
    let parsed; try { parsed = JSON.parse(r.body); } catch {}
    const eps = parsed?.episodes || [];
    if (eps.length > 0 || r.body.length > 50) {
      console.log(`  ?${params} -> ${eps.length} eps | ${r.body.length}B | ${r.body.substring(0,100)}`);
    }
  } catch {}
}

// Non-mobile episodes
for (const params of [`s=${showId}&p=0`, `id=${showId}`, `id=${showId}&season=4`]) {
  try {
    const r = await req(BASE + '/episodes.php?' + params, {headers:hdr});
    let parsed; try { parsed = JSON.parse(r.body); } catch {}
    const eps = parsed?.episodes || [];
    if (eps.length > 0 || r.body.length > 50) {
      console.log(`  /episodes.php?${params} -> ${eps.length} eps | ${r.body.length}B | ${r.body.substring(0,150)}`);
    }
  } catch {}
}

// APPROACH 6: Try /post.php (non-mobile) with different param names
console.log('\n--- Approach 6: Alternative endpoints for show details ---');
const altEndpoints = [
  '/mobile/show.php?id=' + showId,
  '/show.php?id=' + showId,
  '/mobile/detail.php?id=' + showId,
  '/detail.php?id=' + showId,
  '/mobile/series.php?id=' + showId,
  '/series.php?id=' + showId,
  '/mobile/tv.php?id=' + showId,
  '/tv.php?id=' + showId,
  '/mobile/api.php?id=' + showId,
  '/api.php?id=' + showId,
  '/mobile/get.php?id=' + showId,
  '/get.php?id=' + showId,
  '/mobile/seasons.php?id=' + showId,
  '/mobile/post.php?id=' + showId + '&type=seasons',
];
for (const ep of altEndpoints) {
  try {
    const r = await req(BASE + ep, {headers:hdr});
    if (r.status !== 404 && r.body.length > 20) {
      console.log(`  ${ep} -> ${r.status} | ${r.body.length}B | ${r.body.substring(0,150)}`);
    }
  } catch {}
}

// APPROACH 7: Try the /mobile/ page itself — does it embed season data?
console.log('\n--- Approach 7: Mobile home page content ---');
try {
  const r = await req(BASE + '/mobile/', {headers:{'Accept':'text/html'}});
  // Look for JSON data, season arrays, episode listings
  const seasonMatches = r.body.match(/"seasons?\s*"?\s*:\s*\[/gi);
  const episodeMatches = r.body.match(/"episodes?\s*"?\s*:\s*\[/gi);
  const idMatches = r.body.match(/70155584|70037632|82171151/g);
  console.log(`  /mobile/ : ${r.body.length}B | season refs: ${seasonMatches?.length||0} | episode refs: ${episodeMatches?.length||0} | ID matches: ${idMatches?.length||0}`);
  
  // Look for any JSON-like structures
  const jsonBlocks = r.body.match(/\{[^}]{50,300}\}/g);
  if (jsonBlocks) {
    console.log(`  JSON blocks found: ${jsonBlocks.length}`);
    for (const block of jsonBlocks.slice(0,3)) {
      console.log(`    ${block.substring(0,120)}`);
    }
  }
} catch(e) { console.log(`  Error: ${e.message}`); }

console.log('\n=== DONE ===');
