#!/usr/bin/env node
/**
 * Test ADSearch (cross-OTT search) and check data-extra per OTT
 */
import https from 'https';
import { readFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':GATU, 'X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const session = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json','utf8'));
const cookieStr = 'addhash='+session.addhashEncoded+'; t_hash_t='+session.tHashTEncoded+'; lang=eng';
const BASE = session.baseUrl;
const FORM = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};
const ts = Math.floor(Date.now()/1000);

console.log('=== ADSEARCH (CROSS-OTT) + DATA-EXTRA DISCOVERY ===\n');

// Test 1: Search WITH ADSearch=true (search all OTTs)
console.log('--- Test 1: ADSearch=true (cross-OTT search) ---');
const crossOttSearches = ['the boys', 'game of thrones', 'loki', 'severance', 'reacher', 'mandalorian', 'the last of us', 'invincible', 'squid game'];

// Without ADSearch (current OTT only)
console.log('\nWithout ADSearch:');
for (const term of crossOttSearches) {
  const r = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(term) + '&t=' + ts, {
    headers: {'Cookie': cookieStr}
  });
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      console.log('  "' + term + '" -> ' + items.length + ' | id=' + items[0].id + ' "' + items[0].t + '"');
    } else {
      console.log('  "' + term + '" -> 0 results');
    }
  } catch { console.log('  "' + term + '" -> not JSON'); }
}

// With ADSearch=true
console.log('\nWith ADSearch=true:');
for (const term of crossOttSearches) {
  const r = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(term) + '&t=' + ts + '&ADSearch=true', {
    headers: {'Cookie': cookieStr}
  });
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      const best = items.find(i => (i.t||'').toLowerCase().includes(term.split(' ')[0].toLowerCase())) || items[0];
      console.log('  "' + term + '" -> ' + items.length + ' | id=' + best.id + ' "' + best.t + '" | all: ' + items.map(i=>i.t).join(', ').substring(0,80));
    } else {
      console.log('  "' + term + '" -> 0 results (head=' + (p?.head||'') + ')');
    }
  } catch { console.log('  "' + term + '" -> not JSON (' + r.body.length + 'B)'); }
}

// Also try without cookies (both /search.php non-mobile)
console.log('\nWithout cookies, ADSearch=true on /search.php:');
for (const term of crossOttSearches.slice(0, 5)) {
  const r = await req(BASE + '/search.php?s=' + encodeURIComponent(term) + '&ADSearch=true');
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      console.log('  "' + term + '" -> ' + items.length + ' | id=' + items[0].id + ' "' + items[0].t + '"');
    } else {
      console.log('  "' + term + '" -> 0 results');
    }
  } catch { console.log('  "' + term + '" -> not JSON'); }
}

// Test 2: Switch OTT and check data-extra
console.log('\n\n--- Test 2: data-extra per OTT ---');
for (const ott of ['nf', 'pv', 'dp', 'hs']) {
  // Switch
  await req(BASE + '/mobile/setting.php', {
    method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'ott=' + ott
  });
  
  // Fetch home/series
  const r = await req(BASE + '/mobile/series', {'Cookie': cookieStr});
  
  // Extract data-extra
  const extraM = r.body.match(/data-extra="([^"]*)"/);
  const homeM = r.body.match(/data-homeurl="([^"]*)"/);
  console.log('[' + ott.toUpperCase() + '] data-extra="' + (extraM ? extraM[1] : 'NOT FOUND') + '" | data-homeurl="' + (homeM ? homeM[1] : 'NOT FOUND') + '" | page=' + r.body.length + 'B');
}

// Switch back
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'ott=nf'
});
