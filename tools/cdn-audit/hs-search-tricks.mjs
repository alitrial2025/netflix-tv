#!/usr/bin/env node
/**
 * Final HS search tricks — try every way to get HS show IDs without cookies
 */
import https from 'https';

function req(url, opts={}) {
  return new Promise((resolve) => {
    const u = new URL(url);
    https.request({
      hostname: u.hostname, path: u.pathname + u.search, method: opts.method || 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0', 'X-Requested-With': 'XMLHttpRequest', ...opts.headers },
      rejectUnauthorized: false, timeout: 15000
    }, res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    }).on('error', e => resolve({ status: 0, body: e.message })).end(opts.body || undefined);
  });
}

const ts = Math.floor(Date.now() / 1000);
const queries = ['ironheart', 'loki', 'lanterns'];

console.log('=== HS SEARCH TRICKS (ALL NO COOKIES) ===\n');

for (const q of queries) {
  console.log('--- "' + q + '" ---');
  
  // Trick 1: /search.php with ott param
  const r1 = await req('https://net52.cc/search.php?s=' + q + '&t=' + ts + '&ott=hs');
  console.log('  /search.php?ott=hs: ' + r1.body.substring(0, 120));
  
  // Trick 2: /mobile/search.php with ott param
  const r2 = await req('https://net52.cc/mobile/search.php?s=' + q + '&t=' + ts + '&ott=hs');
  console.log('  /mobile/search.php?ott=hs: ' + r2.body.substring(0, 120));
  
  // Trick 3: /mobile/hs/search.php with ADSearch
  const r3 = await req('https://net52.cc/mobile/hs/search.php?s=' + q + '&t=' + ts + '&ADSearch=true');
  console.log('  hs/search.php?ADSearch: ' + r3.body.substring(0, 120));
  
  // Trick 4: Cookie-like header — ott=hs as referer
  const r4 = await req('https://net52.cc/mobile/hs/search.php?s=' + q + '&t=' + ts, {
    headers: { 'Cookie': 'ott=hs', 'Referer': 'https://net52.cc/mobile/series?app=1' }
  });
  console.log('  hs/search Cookie:ott=hs: ' + r4.body.substring(0, 120));
  
  // Trick 5: Just the ott cookie, no session
  const r5 = await req('https://net52.cc/mobile/hs/search.php?s=' + q + '&t=' + ts, {
    headers: { 'Cookie': 'ott=hs; lang=eng' }
  });
  console.log('  hs/search ott+lang cookies: ' + r5.body.substring(0, 120));
  
  console.log();
}
