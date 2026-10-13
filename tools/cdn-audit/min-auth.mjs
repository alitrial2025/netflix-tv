#!/usr/bin/env node
/**
 * FINAL TEST: The fastest possible post.php auth
 * 
 * We know:
 * - /mobile/home gives us addhash instantly (no verify)
 * - post.php needs "some auth" to not return "Invalid User"
 * - The full handshake takes 35s (addhash → userver → verify x25 → t_hash_t)
 * 
 * Question: What's the MINIMUM auth post.php actually checks?
 * Maybe it just checks the addhash cookie format, or checks IP session on server side.
 * Let's try: get addhash, trigger userver, then immediately try post.php
 * without waiting for t_hash_t.
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64) AppleWebKit/537.36 Chrome/133.0.6943.137 Mobile Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers, cookies:[].concat(res.headers['set-cookie']||[])}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const showId = '70155584';
const BASE = 'https://net52.cc';
const hdr = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};

console.log('=== MINIMUM AUTH FOR post.php ===\n');

// Step 1: Get addhash (instant)
console.log('Step 1: Get addhash from /mobile/home...');
const t1 = Date.now();
const homeR = await req(BASE + '/mobile/home');
let addhash = '';
for (const c of homeR.cookies) {
  const m = c.match(/addhash=([^;]+)/);
  if (m) { addhash = decodeURIComponent(m[1]); break; }
}
console.log('addhash:', addhash.substring(0,50) + '...');
console.log('Time:', (Date.now()-t1) + 'ms');

// Step 2: Extract dynamic params from /mobile/home body
const bodyMatch = homeR.body.match(/var\s+Ession_param\s*=\s*["']([^"']+)["']/);
const quryMatch = homeR.body.match(/var\s+Qury\s*=\s*["']([^"']+)["']/);
const vsiteMatch = homeR.body.match(/var\s+Vsite\s*=\s*["']([^"']+)["']/);
const verifyMatch = homeR.body.match(/var\s+verify\s*=\s*["']([^"']+)["']/);
console.log('Qury:', quryMatch ? quryMatch[1] : 'not found');
console.log('Vsite:', vsiteMatch ? vsiteMatch[1] : 'not found');
console.log('verify:', verifyMatch ? verifyMatch[1] : 'not found');

const encoded = encodeURIComponent(addhash);

// Step 3: Trigger userver (fast, async on server)
console.log('\nStep 2: Trigger userver...');
const t2 = Date.now();
const vsite = vsiteMatch ? vsiteMatch[1] : 'userver';
try {
  const uR = await req('https://' + vsite + '.net52.cc/', {
    headers: {'Cookie': 'addhash=' + encoded}
  });
  console.log('userver:', uR.status, '|', (Date.now()-t2) + 'ms');
} catch(e) { console.log('userver error:', e.message); }

// Step 4: Try post.php immediately after userver (no verify wait!)
console.log('\nStep 3: post.php immediately after userver (0s wait)...');
let result = await req(BASE + '/mobile/post.php', {
  method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded+'; lang=eng'}, body:'id='+showId
});
console.log('Result:', result.body.substring(0,200));

// Step 5: Wait 2s and try again
console.log('\nStep 4: Wait 2s + retry...');
await new Promise(r => setTimeout(r, 2000));
result = await req(BASE + '/mobile/post.php', {
  method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded+'; lang=eng'}, body:'id='+showId
});
console.log('Result:', result.body.substring(0,200));

// Step 6: Try verify once and then post.php
console.log('\nStep 5: Single verify call + post.php...');
const verify = verifyMatch ? verifyMatch[1] : '/mobile/verify2.php';
let tHashT = '';
try {
  const vR = await req(BASE + verify, {
    method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded},
    body: 'verify=' + encoded
  });
  for (const c of vR.cookies) {
    const m = c.match(/t_hash_t=([^;]+)/);
    if (m) { tHashT = m[1]; break; }
  }
  console.log('verify result:', vR.status, '| t_hash_t:', tHashT ? 'YES' : 'NO');
} catch(e) { console.log('verify error:', e.message); }

if (tHashT) {
  result = await req(BASE + '/mobile/post.php', {
    method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded+'; t_hash_t='+tHashT+'; lang=eng'}, body:'id='+showId
  });
  console.log('post.php with t_hash_t:', result.body.substring(0,300));
} else {
  console.log('No t_hash_t on first try. Polling verify...');
  for (let i = 0; i < 30; i++) {
    await new Promise(r => setTimeout(r, 1000));
    try {
      const vR = await req(BASE + verify, {
        method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded},
        body: 'verify=' + encoded
      });
      for (const c of vR.cookies) {
        const m = c.match(/t_hash_t=([^;]+)/);
        if (m) { tHashT = m[1]; break; }
      }
      if (tHashT) {
        console.log('t_hash_t received on attempt ' + (i+1) + ' (' + ((i+1)*1000) + 'ms)');
        result = await req(BASE + '/mobile/post.php', {
          method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded+'; t_hash_t='+tHashT+'; lang=eng'}, body:'id='+showId
        });
        const parsed = JSON.parse(result.body);
        const seasons = parsed?.seasons || [];
        console.log('\npost.php SUCCESS! Seasons:', seasons.length);
        for (const s of seasons) {
          console.log('  Season ' + (s.s || s.season) + ': ID=' + s.id);
        }
        console.log('\nTOTAL TIME from start:', (Date.now()-t1) + 'ms');
        break;
      }
    } catch(e) {}
    if (i % 5 === 4) console.log('  ... attempt ' + (i+1) + ', still waiting');
  }
}

if (!tHashT) {
  console.log('\nFailed to get t_hash_t after 30 attempts.');
}
