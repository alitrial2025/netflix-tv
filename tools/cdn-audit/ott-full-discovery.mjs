#!/usr/bin/env node
/**
 * Fresh handshake → Switch each OTT → Browse for content → Get seasons
 * Goal: find what IDs non-Netflix OTTs use and where to get season IDs
 */
import https from 'https';
import { writeFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':GATU, ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const BASE = 'https://net52.cc';
const XHR = {'X-Requested-With':'XMLHttpRequest'};
const FORM = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};

// ═══════════════════════════════════════
// STAGE 1: Fresh handshake
// ═══════════════════════════════════════
console.log('=== STAGE 1: HANDSHAKE ===');
const homeR = await req(BASE + '/mobile/home');
let addhash = '';
for (const c of homeR.cookies) {
  const m = c.match(/addhash=([^;]+)/);
  if (m) { addhash = decodeURIComponent(m[1]); break; }
}
if (!addhash) {
  // Try extracting from body data-addhash
  const bm = homeR.body.match(/data-addhash="([^"]+)"/);
  if (bm) addhash = bm[1];
}
console.log('addhash:', addhash ? addhash.substring(0,40) + '...' : 'NOT FOUND');
if (!addhash) { console.log('FATAL: no addhash'); process.exit(1); }

// Extract params
const quryM = homeR.body.match(/var\s+Qury\s*=\s*["']([^"']+)["']/);
const vsiteM = homeR.body.match(/(?:var\s+)?Vsite2?\s*=\s*["']([^"']+)["']/);
const qury = quryM ? quryM[1] : 'ffr455';
const vsite = vsiteM ? vsiteM[1] : 'userver';
console.log('Qury:', qury, '| Vsite:', vsite);

const encoded = encodeURIComponent(addhash);

// Trigger userver
console.log('Triggering ' + vsite + '.net52.cc...');
try { await req('https://' + vsite + '.net52.cc/?' + qury + '=' + encoded + '&a=y&t=' + Math.random()); } catch {}

// Poll verify
console.log('Polling verify...');
let tHashT = '';
for (let i = 0; i < 45; i++) {
  await new Promise(r => setTimeout(r, 1200));
  try {
    const vR = await req(BASE + '/mobile/verify2.php', {
      method:'POST', headers:{...FORM, 'Cookie':'addhash='+encoded},
      body: 'verify=' + encoded
    });
    for (const c of vR.cookies) {
      const m = c.match(/t_hash_t=([^;]+)/);
      if (m) { tHashT = m[1]; break; }
    }
    if (tHashT) {
      console.log('t_hash_t received on attempt ' + (i+1) + ' (' + ((i+1)*1.2).toFixed(1) + 's)');
      break;
    }
    // Check status
    try {
      const p = JSON.parse(vR.body);
      if (i % 10 === 9) console.log('  attempt ' + (i+1) + ': ' + (p.statusup || '...'));
    } catch {}
  } catch {}
}

if (!tHashT) { console.log('FATAL: no t_hash_t after 45 attempts'); process.exit(1); }

const cookieStr = 'addhash=' + encoded + '; t_hash_t=' + tHashT + '; lang=eng';

// Save session
await writeFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json',
  JSON.stringify({domain:'net52.cc', addhashRaw:addhash, addhashEncoded:encoded, tHashTEncoded:tHashT, fetchedAt:Date.now(), baseUrl:BASE}, null, 2));
console.log('Session saved.\n');

// ═══════════════════════════════════════
// STAGE 2: Browse each OTT
// ═══════════════════════════════════════
console.log('=== STAGE 2: BROWSE EACH OTT ===');

const results = {};

for (const ott of ['nf', 'pv', 'dp', 'hs']) {
  console.log('\n--- OTT: ' + ott.toUpperCase() + ' ---');
  
  // Switch OTT
  const setR = await req(BASE + '/mobile/setting.php', {
    method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'ott=' + ott
  });
  console.log('setting.php:', setR.body);
  
  // Fetch series page
  const seriesR = await req(BASE + '/mobile/series', {'Cookie': cookieStr});
  console.log('series page:', seriesR.body.length + 'B');
  
  // Extract poster IDs
  const posterRe = /imgcdn[^"']*\/(\d{7,9})\./g;
  const ids = new Set();
  let m;
  while ((m = posterRe.exec(seriesR.body)) !== null) ids.add(m[1]);
  console.log('Poster IDs:', ids.size);
  
  // Extract titles
  const titleRe = /class="poster-title[^"]*"[^>]*>([^<]+)/g;
  const titles = [];
  while ((m = titleRe.exec(seriesR.body)) !== null) titles.push(m[1].trim());
  
  // Try alt tags if no poster-title
  if (titles.length === 0) {
    const altRe = /alt="([^"]{3,60})"/g;
    while ((m = altRe.exec(seriesR.body)) !== null) {
      if (!m[1].match(/logo|img|icon|Netflix|Mirror/i)) titles.push(m[1]);
    }
  }
  
  console.log('Titles found:', titles.length);
  if (titles.length) console.log('Sample:', titles.slice(0,5).join(' | '));
  
  // Get seasons for first 5 IDs via post.php
  const testIds = [...ids].slice(0, 5);
  const ottSeasons = [];
  
  for (const id of testIds) {
    const pr = await req(BASE + '/mobile/post.php', {
      method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'id=' + id
    });
    try {
      const parsed = JSON.parse(pr.body);
      const seasons = parsed?.seasons || [];
      if (seasons.length > 0) {
        console.log('  id=' + id + ': ' + seasons.length + ' seasons');
        for (const s of seasons.slice(0,2)) {
          console.log('    S' + (s.s||'?') + ': ID=' + s.id + ' (numeric=' + /^\d+$/.test(s.id) + ')');
        }
        ottSeasons.push({showId: id, seasons});
      } else if (parsed?.error) {
        console.log('  id=' + id + ': ' + parsed.error);
      } else {
        console.log('  id=' + id + ': no seasons (movie?)');
      }
    } catch { console.log('  id=' + id + ': parse error'); }
  }
  
  results[ott] = { posterCount: ids.size, posterIds: [...ids].slice(0,10), titles: titles.slice(0,5), seasons: ottSeasons };
}

// ═══════════════════════════════════════
// STAGE 3: Cross-check with original OTT
// ═══════════════════════════════════════
console.log('\n\n=== STAGE 3: CROSS-CHECK IDS WITH ORIGINAL OTTS ===');

// For PV shows, check if IDs exist on Netflix or Prime
for (const ott of ['pv', 'dp', 'hs']) {
  const data = results[ott];
  if (!data || data.posterCount === 0) continue;
  
  console.log('\n--- ' + ott.toUpperCase() + ' IDs on netflix.com ---');
  for (const id of data.posterIds.slice(0, 5)) {
    try {
      const r = await req('https://www.netflix.com/title/' + id, {
        headers: {'Accept':'text/html','Accept-Language':'en-US'}
      });
      const hasTitle = r.body.match(/"name"\s*:\s*"([^"]+)"/);
      const isNf = r.body.length > 50000;
      console.log('  id=' + id + ': ' + (isNf ? 'NETFLIX "' + (hasTitle ? hasTitle[1] : '?') + '"' : 'NOT on Netflix (' + r.status + ', ' + r.body.length + 'B)'));
    } catch(e) { console.log('  id=' + id + ': error'); }
  }
}

// ═══════════════════════════════════════
// STAGE 4: Test netflix.com extraction for non-NF OTTs
// ═══════════════════════════════════════
console.log('\n\n=== STAGE 4: SEASON EXTRACTION FOR NON-NF OTTS ===');

for (const ott of ['pv', 'dp', 'hs']) {
  const data = results[ott];
  if (!data?.seasons?.length) continue;
  
  const show = data.seasons[0];
  console.log('\n[' + ott.toUpperCase() + '] Testing season extraction for id=' + show.showId);
  
  // Try netflix.com
  try {
    const r = await req('https://www.netflix.com/title/' + show.showId, {
      headers: {'Accept':'text/html','Accept-Language':'en-US'}
    });
    if (r.body.length > 50000) {
      const idPattern = /\b([5-9]\d{7})\b/g;
      const nfIds = new Set();
      while ((m = idPattern.exec(r.body)) !== null) nfIds.add(m[1]);
      console.log('  netflix.com: ' + nfIds.size + ' IDs found');
      
      // Check if known season IDs are in the page
      for (const s of show.seasons.slice(0,3)) {
        console.log('  Season ' + (s.s||'?') + ' ID=' + s.id + ' in page: ' + nfIds.has(s.id));
      }
    } else {
      console.log('  NOT on netflix.com (' + r.body.length + 'B)');
    }
  } catch(e) { console.log('  Error:', e.message); }
}

// Switch back to nf
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'ott=nf'
});

console.log('\n\n=== SUMMARY ===');
for (const [ott, data] of Object.entries(results)) {
  console.log('[' + ott.toUpperCase() + '] ' + data.posterCount + ' posters | ' + data.seasons.length + ' with seasons');
}
