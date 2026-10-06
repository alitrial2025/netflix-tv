#!/usr/bin/env node
/**
 * Quick handshake → search for non-Netflix OTT content
 * Goal: discover what ID format net52 uses for Prime, Disney+, HBO, etc.
 * Once we know, we apply the same netflix.com extraction trick per OTT.
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers, cookies:[].concat(res.headers['set-cookie']||[])}));
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

console.log('=== OTT ID DISCOVERY — Quick Handshake + Multi-OTT Search ===\n');

// Step 1: Get addhash from /mobile/home
console.log('Step 1: Getting addhash...');
const homeR = await req(BASE + '/mobile/home');
let addhash = '';
for (const c of homeR.cookies) {
  const m = c.match(/addhash=([^;]+)/);
  if (m) { addhash = decodeURIComponent(m[1]); break; }
}
console.log('addhash:', addhash ? addhash.substring(0,40) + '...' : 'NOT FOUND');

// Extract dynamic params from home page body  
const quryMatch = homeR.body.match(/var\s+Qury\s*=\s*["']([^"']+)["']/);
const vsiteMatch = homeR.body.match(/var\s+Vsite\s*=\s*["']([^"']+)["']/);
const verifyMatch = homeR.body.match(/var\s+verify\s*=\s*["']([^"']+)["']/);

// Also try extracting from script blocks more broadly
let qury = quryMatch ? quryMatch[1] : '';
let vsite = vsiteMatch ? vsiteMatch[1] : '';
let verifyPath = verifyMatch ? verifyMatch[1] : '';

// Search in the HTML for these params
if (!qury) {
  const m = homeR.body.match(/["']([a-z]{3,8}\d{2,4})["']/);
  if (m) qury = m[1];
}
if (!vsite) {
  const m = homeR.body.match(/["'](userver|vserver|cserver)["']/);
  if (m) vsite = m[1];
}
if (!verifyPath) {
  const m = homeR.body.match(/(\/mobile\/verify\d?\.php)/);
  if (m) verifyPath = m[1];
}

console.log('Qury:', qury || 'not found');
console.log('Vsite:', vsite || 'not found (using userver)');
console.log('Verify:', verifyPath || 'not found (using /mobile/verify2.php)');

const encoded = encodeURIComponent(addhash);
if (!vsite) vsite = 'userver';
if (!verifyPath) verifyPath = '/mobile/verify2.php';

// Step 2: Trigger userver
console.log('\nStep 2: Triggering ' + vsite + '.net52.cc...');
try {
  await req('https://' + vsite + '.net52.cc/', {
    headers: {'Cookie': 'addhash=' + encoded}
  });
  console.log('Triggered.');
} catch(e) { console.log('Error:', e.message); }

// Step 3: Poll verify for t_hash_t
console.log('\nStep 3: Polling verify...');
let tHashT = '';
for (let i = 0; i < 40; i++) {
  await new Promise(r => setTimeout(r, 1200));
  try {
    const vR = await req(BASE + verifyPath, {
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
  } catch {}
  if (i % 10 === 9) console.log('  attempt ' + (i+1) + '...');
}

if (!tHashT) {
  console.log('Failed to get t_hash_t. Cannot proceed with mobile search.');
  process.exit(1);
}

const cookieStr = 'addhash=' + encoded + '; t_hash_t=' + tHashT + '; lang=eng';

// Step 4: Search for OTT-exclusive content with cookies
console.log('\nStep 4: Searching for OTT exclusives (WITH cookies)...\n');

const ottSearches = [
  // Prime Video exclusives
  {ott: 'Prime', term: 'the boys'},
  {ott: 'Prime', term: 'jack ryan'},
  {ott: 'Prime', term: 'invincible'},
  {ott: 'Prime', term: 'reacher'},
  {ott: 'Prime', term: 'fallout'},
  {ott: 'Prime', term: 'rings of power'},
  {ott: 'Prime', term: 'citadel'},
  // Disney+
  {ott: 'Disney+', term: 'loki'},
  {ott: 'Disney+', term: 'mandalorian'},
  {ott: 'Disney+', term: 'andor'},
  {ott: 'Disney+', term: 'ahsoka'},
  // HBO
  {ott: 'HBO', term: 'the last of us'},
  {ott: 'HBO', term: 'euphoria'},
  {ott: 'HBO', term: 'succession'},
  {ott: 'HBO', term: 'house of dragon'},
  // Apple TV+
  {ott: 'Apple', term: 'severance'},
  {ott: 'Apple', term: 'ted lasso'},
  // Paramount+
  {ott: 'P+', term: 'halo'},
  {ott: 'P+', term: 'yellowstone'},
  // Hulu
  {ott: 'Hulu', term: 'the bear'},
  {ott: 'Hulu', term: 'shogun'},
];

const ts = Math.floor(Date.now()/1000);
const found = [];

for (const {ott, term} of ottSearches) {
  try {
    const r = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(term) + '&t=' + ts, {
      headers: {...XHR, 'Cookie': cookieStr}
    });
    const parsed = JSON.parse(r.body);
    const status = parsed?.status;
    const head = parsed?.head;
    const items = parsed?.searchResult || [];
    
    if (status === 'n' || head === 'Top Searches') {
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> TOP SEARCHES (not real results)');
      continue;
    }
    
    if (items.length > 0) {
      // Find best match
      const exact = items.find(i => {
        const t = (i.t||'').toLowerCase();
        return term.split(' ').every(w => t.includes(w.toLowerCase()));
      });
      const item = exact || items[0];
      const isExact = exact ? 'EXACT' : 'partial';
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> id=' + item.id + ' "' + item.t + '" (' + isExact + ')');
      
      // Check ID format
      const isNumeric = /^\d+$/.test(item.id);
      const isAlpha = /^[A-Z0-9]+$/.test(item.id);
      if (!isNumeric) console.log('         NON-NUMERIC ID: ' + item.id);
      
      if (isExact) found.push({ott, term, id: item.id, title: item.t});
    } else {
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> NOT FOUND (' + items.length + ' results, head=' + head + ')');
    }
  } catch(e) { console.log('[' + ott.padEnd(7) + '] "' + term + '" -> ERROR: ' + e.message); }
}

// Step 5: For found non-Netflix shows, test post.php to see season structure
console.log('\n\n--- Step 5: post.php for found shows ---');
for (const show of found) {
  try {
    const r = await req(BASE + '/mobile/post.php', {
      method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'id=' + show.id
    });
    const parsed = JSON.parse(r.body);
    const seasons = parsed?.seasons || [];
    console.log('[' + show.ott + '] "' + show.title.substring(0,30) + '": ' + seasons.length + ' seasons');
    for (const s of seasons.slice(0,5)) {
      console.log('  S' + (s.s||s.season||'?') + ': ID=' + s.id + ' (ID format: ' + ((/^\d+$/.test(s.id)) ? 'numeric' : 'alphanumeric') + ')');
    }
    if (seasons.length > 5) console.log('  ... +' + (seasons.length-5) + ' more');
  } catch(e) { console.log('[' + show.ott + '] post.php error:', e.message); }
}

// Summary
console.log('\n========================================');
console.log('OTT ID FORMAT SUMMARY');
console.log('========================================');
for (const show of found) {
  const isNumeric = /^\d+$/.test(show.id);
  console.log('[' + show.ott + '] "' + show.title + '" id=' + show.id + ' format=' + (isNumeric ? 'NUMERIC (Netflix-style)' : 'ALPHANUMERIC'));
}
