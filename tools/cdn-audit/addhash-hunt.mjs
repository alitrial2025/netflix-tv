#!/usr/bin/env node
/**
 * Deep addhash hunt + post.php test on net52.cc ONLY
 * Finds which exact path returns the addhash and tests if post.php
 * works with ONLY addhash (skipping the entire 35s verify chain).
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const headers = { 
      'User-Agent': 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0',
      ...opts.headers 
    };
    const r = https.request({ 
      hostname: u.hostname, path: u.pathname+u.search, 
      method: opts.method||'GET', headers, rejectUnauthorized:false, timeout:15000 
    }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({
        status:res.statusCode, body:d, headers:res.headers,
        location: res.headers['location'] || '',
        cookies: [].concat(res.headers['set-cookie'] || [])
      }));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const showId = '70155584';
const BASE = 'https://net52.cc';

console.log('=== ADDHASH HUNT on net52.cc ===\n');

// Try every possible path that could return addhash
const paths = [
  '/', '/home', '/index.php', '/mobile/', '/mobile/index.php', 
  '/mobile/home', '/mobile/home.php', '/home.php',
  '/mobile/search.php?s=a', '/search.php?s=a'
];

let addhash = '';
let addhashCookie = '';

for (const p of paths) {
  try {
    const r = await req(BASE + p);
    
    // Check Set-Cookie for addhash
    let foundCookie = '';
    for (const c of r.cookies) {
      const m = c.match(/addhash=([^;]+)/);
      if (m) { foundCookie = m[1]; break; }
    }
    
    // Check body for embedded addhash
    let foundInBody = '';
    const bodyMatch = r.body.match(/data-(?:hash|addhash|token)=["']([^"']+)["']/) ||
                      r.body.match(/(?:var\s+|window\.)addhash\s*=\s*["']([^"']+)["']/) ||
                      r.body.match(/addhash['"]\s*:\s*["']([^"']+)["']/) ||
                      r.body.match(/addhash\s*=\s*["']([^"']+)["']/);
    if (bodyMatch) foundInBody = bodyMatch[1];
    
    const icon = (foundCookie || foundInBody) ? 'FOUND!' : '-';
    console.log(icon, p, '-> HTTP', r.status, '|', r.body.length+'B | cookie:', foundCookie ? foundCookie.substring(0,40)+'...' : 'none', '| body:', foundInBody ? foundInBody.substring(0,40)+'...' : 'none');
    
    if (r.location) console.log('   -> redirects to:', r.location);
    
    // Show all Set-Cookie headers
    if (r.cookies.length > 0) {
      for (const c of r.cookies) {
        console.log('   Set-Cookie:', c.substring(0,100));
      }
    }
    
    if (foundCookie && !addhash) { addhash = decodeURIComponent(foundCookie); addhashCookie = foundCookie; }
    if (foundInBody && !addhash) { addhash = foundInBody; addhashCookie = encodeURIComponent(foundInBody); }
    
    // If redirect, follow it
    if (r.location && r.status >= 300 && r.status < 400) {
      const followUrl = r.location.startsWith('http') ? r.location : BASE + r.location;
      try {
        const r2 = await req(followUrl);
        for (const c of r2.cookies) {
          const m = c.match(/addhash=([^;]+)/);
          if (m && !addhash) { 
            addhash = decodeURIComponent(m[1]); 
            addhashCookie = m[1];
            console.log('   FOUND in redirect! addhash:', addhash.substring(0,40)+'...');
          }
        }
        const bm = r2.body.match(/data-(?:hash|addhash|token)=["']([^"']+)["']/) ||
                    r2.body.match(/addhash\s*=\s*["']([^"']+)["']/);
        if (bm && !addhash) {
          addhash = bm[1];
          addhashCookie = encodeURIComponent(bm[1]);
          console.log('   FOUND in redirect body! addhash:', addhash.substring(0,40)+'...');
        }
      } catch(e) {}
    }
  } catch(e) { console.log('-', p, '-> ERROR:', e.message); }
}

// Try /home with explicit Accept: text/html (browser-like)
console.log('\n--- Trying with full browser headers ---');
try {
  const r = await req(BASE + '/home', {
    headers: {
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'Accept-Language': 'en-US,en;q=0.9',
      'sec-ch-ua': '"Chromium";v="133"',
      'sec-ch-ua-mobile': '?1',
      'sec-fetch-dest': 'document',
      'sec-fetch-mode': 'navigate',
      'sec-fetch-site': 'none',
      'Upgrade-Insecure-Requests': '1'
    }
  });
  console.log('/home (browser):', r.status, '|', r.body.length+'B | cookies:', r.cookies.length);
  for (const c of r.cookies) console.log('  Cookie:', c.substring(0,120));
  
  // Check body for any hash/token
  const matches = r.body.match(/[a-f0-9]{32}::[a-f0-9]+::\d+::\w+/g);
  if (matches) {
    console.log('  Token patterns found in body:', matches.length);
    for (const m of matches) console.log('    ', m.substring(0,60));
  }
  
  // Check for data attributes
  const dataAttrs = r.body.match(/data-\w+="[^"]+"/g);
  if (dataAttrs) {
    console.log('  Data attributes:', dataAttrs.length);
    for (const d of dataAttrs.slice(0,5)) console.log('    ', d.substring(0,80));
  }
  
  // Show a chunk of the body around any "hash" or "addhash" mention
  const idx = r.body.indexOf('addhash');
  if (idx >= 0) {
    console.log('  addhash context:', r.body.substring(Math.max(0,idx-50), idx+100));
  }
  const idx2 = r.body.indexOf('hash');
  if (idx2 >= 0) {
    console.log('  hash context:', r.body.substring(Math.max(0,idx2-50), idx2+100));
  }
  
  if (r.location) {
    console.log('  Redirects to:', r.location);
    // Follow
    const followUrl = r.location.startsWith('http') ? r.location : BASE + r.location;
    const r2 = await req(followUrl, {
      headers: {
        'Accept': 'text/html,application/xhtml+xml',
        'sec-fetch-dest': 'document',
        'sec-fetch-mode': 'navigate',
      }
    });
    console.log('  Followed:', r2.status, '|', r2.body.length+'B');
    for (const c of r2.cookies) console.log('    Cookie:', c.substring(0,120));
    const idx3 = r2.body.indexOf('addhash');
    if (idx3 >= 0) console.log('    addhash context:', r2.body.substring(Math.max(0,idx3-50), idx3+150));
    const idx4 = r2.body.indexOf('hash');
    if (idx4 >= 0) console.log('    hash context:', r2.body.substring(Math.max(0,idx4-50), idx4+150));
    
    const tokens = r2.body.match(/[a-f0-9]{32}::[a-f0-9]+/g);
    if (tokens) {
      console.log('    Token patterns:', tokens.length);
      for (const t of tokens) console.log('      ', t.substring(0,60));
    }
  }
} catch(e) { console.log('browser headers error:', e.message); }

// If we found addhash, test post.php with it
if (addhash) {
  console.log('\n========================================');
  console.log('ADDHASH FOUND! Testing post.php...');
  console.log('========================================');
  console.log('addhash:', addhash.substring(0,50) + '...');
  console.log('parts:', addhash.split('::').length);
  
  // Test: post.php with ONLY addhash, no t_hash_t, no verify
  try {
    const r = await req(BASE + '/mobile/post.php', {
      method:'POST', 
      headers:{'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest','Cookie':'addhash='+addhashCookie+'; lang=eng'}, 
      body:'id='+showId
    });
    console.log('\npost.php result:', r.status, '|', r.body.length+'B');
    console.log('body:', r.body.substring(0,500));
    
    const parsed = JSON.parse(r.body);
    if (parsed.seasons) {
      console.log('\nSEASONS FOUND WITHOUT VERIFY!');
      console.log('Count:', parsed.seasons.length);
      for (const s of parsed.seasons) {
        console.log('  Season', s.s || s.season, 'ID:', s.id);
      }
    }
  } catch(e) { console.log('post.php error:', e.message); }
} else {
  console.log('\n========================================');
  console.log('NO ADDHASH FOUND IN ANY PATH');
  console.log('The addhash might be generated client-side by JavaScript');
  console.log('========================================');
}
