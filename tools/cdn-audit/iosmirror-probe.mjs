#!/usr/bin/env node
import https from 'https';

function req(url, hdrs={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.get(url, {headers:{'User-Agent':'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/133.0 Mobile Safari/537.36','X-Requested-With':'XMLHttpRequest',...hdrs},rejectUnauthorized:false,timeout:15000}, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
        req(loc, hdrs).then(resolve).catch(reject);
        res.resume();
        return;
      }
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    }).on('error',e=>resolve({status:0,body:e.message,cookies:[]}));
  });
}

const GATU_UA = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';
const BASE = 'https://iosmirror.app';

console.log('=== iosmirror.app EXPLORATION ===\n');

// Search 
const searches = ['the boys', 'game of thrones', 'loki', 'severance', 'smallville', 'squid game', 'mandalorian', 'the last of us'];

for (const ep of ['/search.php', '/mobile/search.php']) {
  console.log('--- ' + ep + ' ---');
  for (const s of searches) {
    const r = await req(BASE + ep + '?s=' + encodeURIComponent(s));
    const isJson = r.body.startsWith('{') || r.body.startsWith('[');
    if (isJson) {
      try {
        const p = JSON.parse(r.body);
        const items = p?.searchResult || [];
        if (items.length > 0) {
          console.log('  "' + s + '" -> ' + items.length + ' results: id=' + items[0].id + ' "' + items[0].t + '"');
        } else {
          console.log('  "' + s + '" -> ' + (p?.head || 'empty'));
        }
      } catch { console.log('  "' + s + '" -> parse error'); }
    } else {
      console.log('  Not JSON (' + r.body.length + 'B) - skipping endpoint');
      break;
    }
  }
}

// With Gatu UA
console.log('\n--- /mobile/search.php with OS.Gatu UA ---');
for (const s of searches) {
  const r = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(s), {'User-Agent': GATU_UA});
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      console.log('  "' + s + '" -> ' + items.length + ' results: id=' + items[0].id + ' "' + items[0].t + '"');
    } else {
      console.log('  "' + s + '" -> ' + (p?.head || p?.status || 'empty'));
    }
  } catch { console.log('  "' + s + '" -> not JSON (' + r.body.length + 'B)'); }
}

// API
console.log('\n--- API endpoints ---');
let r = await req(BASE + '/mobile/api.php');
console.log('api.php:', r.body.substring(0, 200));

// episodes
console.log('\n--- episodes.php ---');
r = await req(BASE + '/mobile/episodes.php?s=70037632&p=0');
console.log('episodes (Smallville S4):', r.body.substring(0, 200));

r = await req(BASE + '/episodes.php?s=70037632&p=0');
console.log('episodes non-mobile:', r.body.substring(0, 200));

// Home with Gatu
console.log('\n--- /mobile/home (Gatu UA) ---');
r = await req(BASE + '/mobile/home', {'User-Agent': GATU_UA});
console.log('Status:', r.status, '| Size:', r.body.length + 'B');
if (r.cookies.length) {
  for (const c of r.cookies) console.log('  cookie:', c.substring(0,100));
}

// Check the HTML for OTT categories
if (r.body.length > 1000) {
  const ottPatterns = /prime|amazon|disney|hbo|apple|hulu|paramount|hotstar/gi;
  const matches = r.body.match(ottPatterns);
  if (matches) console.log('  OTT mentions:', [...new Set(matches.map(m=>m.toLowerCase()))].join(', '));
  
  // Extract PHP endpoints
  const phpPattern = /["']((?:\/mobile\/)?[a-z_]+\.php)['"]/g;
  const phps = new Set();
  let m;
  while ((m = phpPattern.exec(r.body)) !== null) phps.add(m[1]);
  if (phps.size) {
    console.log('  PHP endpoints:');
    for (const p of phps) console.log('    ' + p);
  }
}
