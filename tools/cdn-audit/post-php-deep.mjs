#!/usr/bin/env node
/**
 * Deep investigation: How does their app/site call post.php?
 * Tests every possible way to get season data without the full handshake.
 */
import https from 'https';
import http from 'http';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'https:' ? https : http;
    const headers = { 'User-Agent': 'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/133.0 Mobile Safari/537.36', ...opts.headers };
    const r = mod.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET', headers, rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode,body:d,headers:res.headers}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const showId = '70155584';
const hdr = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};

console.log('=== THINKING BEYOND: How does their app/site get seasons? ===\n');

// 1. Quick addhash from homepage (one fetch, no handshake)
console.log('--- Step 1: Quick addhash from homepage ---');
let addhash = '';
try {
  const r = await req('https://net52.cc/home');
  const cookies = [].concat(r.headers['set-cookie'] || []);
  for (const c of cookies) {
    const m = c.match(/addhash=([^;]+)/);
    if (m) { addhash = decodeURIComponent(m[1]); break; }
  }
  if (!addhash) {
    const m = r.body.match(/data-(?:hash|addhash|token)=["']([^"']+)["']/) ||
              r.body.match(/addhash\s*=\s*["']([^"']+)["']/);
    if (m) addhash = m[1];
  }
  console.log('addhash found:', addhash ? 'YES (' + addhash.substring(0,30) + '...)' : 'NO');
  console.log('addhash length:', addhash.length);
} catch(e) { console.log('homepage error:', e.message); }

// 2. post.php with ONLY addhash (no t_hash_t, no verify, no userver)
if (addhash) {
  console.log('\n--- Step 2: post.php with ONLY addhash cookie (skip entire verify chain) ---');
  const encoded = encodeURIComponent(addhash);
  try {
    const r = await req('https://net52.cc/mobile/post.php', {
      method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded+'; lang=eng'}, body:'id='+showId
    });
    console.log('Result:', r.status, '|', r.body.length+'B');
    console.log('Body:', r.body.substring(0,300));
  } catch(e) { console.log('Error:', e.message); }
}

// 3. Show page HTML — maybe seasons are embedded
console.log('\n--- Step 3: Show page HTML (embedded seasons?) ---');
const paths = ['/watch/'+showId, '/title/'+showId, '/'+showId, '/mobile/watch/'+showId, 
               '/mobile/title.php?id='+showId, '/mobile/'+showId, '/home?id='+showId,
               '/mobile/home?id='+showId, '/mobile/index.php?id='+showId];
for (const p of paths) {
  try {
    const r = await req('https://net52.cc'+p);
    const preview = r.body.substring(0,100).replace(/\n/g,' ').replace(/\r/g,'');
    console.log(p, '-> HTTP', r.status, '|', r.body.length+'B |', preview);
  } catch(e) { console.log(p, '-> ERROR:', e.message); }
}

// 4. netmirror.app — different domain, different rules?
console.log('\n--- Step 4: netmirror.app (different domain) ---');
for (const path of ['/mobile/post.php', '/post.php']) {
  try {
    const r = await req('https://netmirror.app'+path, {method:'POST', headers:hdr, body:'id='+showId});
    console.log('netmirror.app'+path+':', r.status, '|', r.body.length+'B |', r.body.substring(0,250));
  } catch(e) { console.log('netmirror.app'+path+':', e.message); }
}

// 5. non-mobile /post.php with addhash
if (addhash) {
  console.log('\n--- Step 5: /post.php (non-mobile) with addhash ---');
  const encoded = encodeURIComponent(addhash);
  try {
    const r = await req('https://net52.cc/post.php', {
      method:'POST', headers:{...hdr, 'Cookie':'addhash='+encoded}, body:'id='+showId
    });
    console.log('Result:', r.status, '|', r.body.length+'B |', r.body.substring(0,250));
  } catch(e) { console.log('Error:', e.message); }
}

// 6. addhash as body/query param instead of cookie
console.log('\n--- Step 6: addhash as body/query param ---');
if (addhash) {
  const encoded = encodeURIComponent(addhash);
  try {
    let r = await req('https://net52.cc/mobile/post.php', {method:'POST', headers:hdr, body:'id='+showId+'&addhash='+encoded});
    console.log('body addhash:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
  } catch(e) { console.log('body addhash error:', e.message); }
  try {
    let r = await req('https://net52.cc/mobile/post.php?addhash='+encoded, {method:'POST', headers:hdr, body:'id='+showId});
    console.log('query addhash:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
  } catch(e) { console.log('query addhash error:', e.message); }
  try {
    let r = await req('https://net52.cc/mobile/post.php?in='+encoded, {method:'POST', headers:hdr, body:'id='+showId});
    console.log('query ?in=addhash:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
  } catch(e) { console.log('query ?in= error:', e.message); }
}

// 7. ffr455/Qury param
console.log('\n--- Step 7: post.php with ffr455/Qury ---');
try {
  let r = await req('https://net52.cc/mobile/post.php', {method:'POST', headers:hdr, body:'id='+showId+'&ffr455=1'});
  console.log('+ffr455 body:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
} catch(e) {}
try {
  let r = await req('https://net52.cc/mobile/post.php?ffr455=1', {method:'POST', headers:hdr, body:'id='+showId});
  console.log('?ffr455 query:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
} catch(e) {}

// 8. Try GET on post.php with different approaches
console.log('\n--- Step 8: Different HTTP methods ---');
try {
  let r = await req('https://net52.cc/mobile/post.php?id='+showId);
  console.log('GET /mobile/post.php:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
} catch(e) {}
try {
  let r = await req('https://net52.cc/mobile/post.php', {method:'PUT', headers:hdr, body:'id='+showId});
  console.log('PUT /mobile/post.php:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
} catch(e) {}

// 9. Try mobidetect.art
console.log('\n--- Step 9: mobidetect.art ---');
try {
  let r = await req('https://mobidetect.art/mobile/post.php', {method:'POST', headers:hdr, body:'id='+showId});
  console.log('mobidetect.art:', r.status, '|', r.body.length+'B |', r.body.substring(0,200));
} catch(e) { console.log('mobidetect.art:', e.message); }

// 10. Try with just the addhash hash parts as cookie (maybe it only checks format)
if (addhash) {
  console.log('\n--- Step 10: Minimal cookie variations ---');
  const parts = addhash.split('::');
  console.log('addhash parts:', parts.length, '| first part:', parts[0]?.substring(0,20));
  
  // Try with fake/minimal addhash
  const fakes = [
    'test::test::test::ek',
    'x::y::z',
    parts[0] + '::fake::fake::ek',
    '1',
  ];
  for (const fake of fakes) {
    try {
      const r = await req('https://net52.cc/mobile/post.php', {
        method:'POST', headers:{...hdr, 'Cookie':'addhash='+encodeURIComponent(fake)+'; lang=eng'}, body:'id='+showId
      });
      console.log('fake "'+fake.substring(0,25)+'":', r.status, '|', r.body.length+'B |', r.body.substring(0,150));
    } catch(e) {}
  }
}
