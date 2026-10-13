#!/usr/bin/env node
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
const BASE = 'https://net52.cc';
const FORM = {'Content-Type':'application/x-www-form-urlencoded'};

console.log('=== OTT BROWSE CONTENT COMPARISON ===\n');

for (const ott of ['nf', 'pv', 'dp', 'hs']) {
  // Switch OTT
  await req(BASE + '/mobile/setting.php', {
    method: 'POST', headers: {...FORM, 'Cookie': cookieStr}, body: 'ott=' + ott
  });
  
  // Fetch series page
  const r = await req(BASE + '/mobile/series', {'Cookie': cookieStr});
  
  // Extract poster IDs
  const posterRe = /imgcdn[^"']*\/(\d{7,9})\./g;
  const ids = new Set();
  let m;
  while ((m = posterRe.exec(r.body)) !== null) ids.add(m[1]);
  
  // Extract titles from alt tags or title attributes
  const titleRe = /(?:alt|title)="([^"]{3,60})"/g;
  const titles = [];
  while ((m = titleRe.exec(r.body)) !== null) {
    if (!m[1].includes('Netflix') && !m[1].includes('img') && !m[1].includes('logo')) {
      titles.push(m[1]);
    }
  }
  
  console.log('[' + ott.toUpperCase() + '] /mobile/series: ' + r.body.length + 'B | ' + ids.size + ' posters');
  console.log('  IDs sample:', [...ids].slice(0,8).join(', '));
  if (titles.length) console.log('  Titles:', titles.slice(0,5).join(' | '));
  
  // Try post.php for a few IDs to get seasons
  const testIds = [...ids].slice(0, 3);
  for (const id of testIds) {
    const pr = await req(BASE + '/mobile/post.php', {
      method: 'POST', headers: {...FORM, 'Cookie': cookieStr}, body: 'id=' + id
    });
    try {
      const parsed = JSON.parse(pr.body);
      const seasons = parsed?.seasons || [];
      const title = parsed?.title || parsed?.t || '?';
      if (seasons.length > 0) {
        console.log('  post.php id=' + id + ': "' + title + '" -> ' + seasons.length + ' seasons');
        for (const s of seasons.slice(0,3)) {
          console.log('    S' + (s.s||'?') + ': ID=' + s.id);
        }
      } else if (parsed?.error) {
        console.log('  post.php id=' + id + ': ' + parsed.error);
      } else {
        console.log('  post.php id=' + id + ': ' + pr.body.substring(0,100));
      }
    } catch { console.log('  post.php id=' + id + ': not JSON'); }
  }
  console.log('');
}
