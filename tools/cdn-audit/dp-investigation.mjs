#!/usr/bin/env node
/**
 * Disney+ deep investigation
 * The catalog dump got 167KB pages with 167 IDs, but /mobile/dp/ returned 404
 * Maybe DP uses the MAIN /mobile/ endpoints with ott=dp cookie
 * (like nf-custom.js: extraurl + "/mobile/post.php" where extraurl could be "")
 */
import https from 'https';
import crypto from 'crypto';
import { readFile, writeFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0',
        'Accept':'*/*','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    }).on('error',e=>resolve({status:0,body:e.message,cookies:[]})).end(opts.body||undefined);
  });
}

const BASE = 'https://net52.cc';
const FORM = {'Content-Type':'application/x-www-form-urlencoded'};
const H1 = '235ca31540ab8d90fcef4a00de8a247c';

// Load existing session
let cookieStr = '';
try {
  const data = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json', 'utf8'));
  cookieStr = 'addhash=' + data.addhashEncoded + '; t_hash_t=' + data.tHashTEncoded + '; lang=eng';
} catch { console.log('No session found'); process.exit(1); }

console.log('╔══════════════════════════════════════════════╗');
console.log('║  DISNEY+ (DP) — Deep Investigation           ║');
console.log('╚══════════════════════════════════════════════╝\n');

// Step 1: Switch to DP and get the series page
console.log('=== 1. SWITCH TO DP & GET BROWSE PAGE ===\n');
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie':cookieStr}, body:'ott=dp'
});
const dpCookie = cookieStr + '; ott=dp';

const seriesR = await req(BASE + '/mobile/series?app=1', {headers:{'Cookie':dpCookie}});
console.log('DP series page: ' + seriesR.status + ' | ' + seriesR.body.length + 'B');

// Save the DP page for analysis
await writeFile('tools/cdn-audit/dp-series-page.html', seriesR.body);

// Extract data-extra from body tag
const extraM = seriesR.body.match(/data-extra="([^"]*)"/);
console.log('data-extra: "' + (extraM ? extraM[1] : 'NOT FOUND') + '"');

// Extract data-post IDs
const postRe = /data-post="([^"]+)"/g;
const dpIds = new Set();
let m;
while ((m = postRe.exec(seriesR.body)) !== null) dpIds.add(m[1]);
console.log('data-post IDs: ' + dpIds.size);

// Extract poster image paths to find DP imgcdn prefix
const imgRe = /imgcdn\.kim\/([^/"]+)\/(\d+)\/([^"]+\.(?:jpg|webp))/g;
const imgPrefixes = new Set();
while ((m = imgRe.exec(seriesR.body)) !== null) {
  imgPrefixes.add(m[1]);
}
console.log('imgcdn prefixes: ' + [...imgPrefixes].join(', '));

// Show sample IDs
const idList = [...dpIds];
console.log('\nSample IDs: ' + idList.slice(0,10).join(', '));

// Determine ID format
const allNumeric = idList.every(id => /^\d+$/.test(id));
const allAlpha = idList.every(id => /^[A-Za-z0-9]+$/.test(id));
console.log('Format: numeric=' + allNumeric + ' alpha=' + allAlpha + ' length=' + (idList[0]||'').length);

// Step 2: Test different endpoint paths with DP IDs
console.log('\n=== 2. TEST ENDPOINT PATHS ===\n');

const ts = Math.floor(Date.now()/1000);
const testId = idList[0];
console.log('Testing ID: ' + testId + '\n');

// Try EVERY possible path pattern
const paths = [
  // DP-specific prefix
  '/mobile/dp/post.php?id=' + testId + '&t=' + ts,
  '/mobile/dp/episodes.php?s=' + testId + '&p=0',
  '/mobile/dp/playlist.php?id=' + testId,
  // Main prefix (with ott=dp cookie)
  '/mobile/post.php?id=' + testId + '&t=' + ts,
  '/mobile/episodes.php?s=' + testId + '&p=0',
  '/mobile/playlist.php?id=' + testId,
  // Search
  '/mobile/dp/search.php?s=loki',
  '/mobile/search.php?s=loki',
  '/search.php?s=loki',
];

for (const path of paths) {
  const r = await req(BASE + path, {headers:{'Cookie':dpCookie}});
  const preview = r.body.substring(0,100).replace(/\n/g,' ').replace(/<[^>]+>/g,'');
  console.log(path.substring(0,55).padEnd(56) + r.status + ' | ' + preview);
}

// Step 3: Test WITHOUT cookies (the zero-cookie test)
console.log('\n=== 3. ZERO-COOKIE TEST ===\n');

for (const path of [
  '/mobile/dp/post.php?id=' + testId + '&t=' + ts,
  '/mobile/dp/episodes.php?s=' + testId + '&p=0',
  '/mobile/dp/playlist.php?id=' + testId,
  '/mobile/post.php?id=' + testId + '&t=' + ts,
  '/mobile/episodes.php?s=' + testId + '&p=0',
  '/mobile/playlist.php?id=' + testId,
]) {
  const r = await req(BASE + path);
  const preview = r.body.substring(0,80).replace(/\n/g,' ').replace(/<[^>]+>/g,'');
  console.log('NO-COOKIE ' + path.substring(0,50).padEnd(51) + r.status + ' | ' + preview);
}

// Step 4: Test HLS for DP content
console.log('\n=== 4. HLS TEST ===\n');
const h2 = crypto.createHash('md5').update(ts.toString() + testId).digest('hex');
const tokenEk = H1 + '::' + h2 + '::' + ts + '::ek::m';
const tokenEb = H1 + '::' + h2 + '::' + ts + '::eb::m';

for (const [label, path] of [
  ['dp/hls ek', '/mobile/dp/hls/' + testId + '.m3u8?in=' + encodeURIComponent(tokenEk)],
  ['dp/hls eb', '/mobile/dp/hls/' + testId + '.m3u8?in=' + encodeURIComponent(tokenEb)],
  ['hls ek',    '/mobile/hls/' + testId + '.m3u8?in=' + encodeURIComponent(tokenEk)],
  ['hls eb',    '/mobile/hls/' + testId + '.m3u8?in=' + encodeURIComponent(tokenEb)],
]) {
  const r = await req(BASE + path);
  const hasCdn = r.body.includes('cdn') && !r.body.includes('220884');
  console.log(label + ': ' + r.status + ' | ' + r.body.length + 'B | cdn=' + hasCdn);
  if (r.body.length > 30 && r.body.length < 2000) console.log('  ' + r.body.substring(0,200));
}

// Step 5: Try more IDs with post.php + ott=dp cookie
console.log('\n=== 5. BATCH POST.PHP (with cookie) ===\n');
let found = 0;
for (const id of idList.slice(0, 10)) {
  const r = await req(BASE + '/mobile/post.php?id=' + id + '&t=' + ts, {
    headers: {'Cookie': dpCookie}
  });
  try {
    const d = JSON.parse(r.body);
    if (d.episodes || d.cast || d.desc) {
      const eps = d.episodes||[];
      console.log('✅ id=' + id + ' | ' + eps.length + ' eps | cast: ' + (d.cast||'').substring(0,50));
      found++;
    } else if (d.error) {
      console.log('❌ id=' + id + ': ' + d.error);
    }
  } catch {
    console.log('? id=' + id + ': ' + r.body.substring(0,60).replace(/\n/g,' '));
  }
}
console.log('\nFound details for ' + found + '/' + Math.min(idList.length,10) + ' IDs');

// Switch back to NF
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie':cookieStr}, body:'ott=nf'
});
