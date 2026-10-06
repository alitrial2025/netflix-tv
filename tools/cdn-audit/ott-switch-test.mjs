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

console.log('=== OTT SWITCHING via setting.php ===\n');

const otts = ['pv', 'dp', 'hs', 'nf'];

for (const ott of otts) {
  console.log('--- Switching to OTT: ' + ott + ' ---');
  
  // 1. Call setting.php to switch OTT
  const setR = await req(BASE + '/mobile/setting.php', {
    method: 'POST', headers: {...FORM, 'Cookie': cookieStr}, body: 'ott=' + ott
  });
  console.log('setting.php:', setR.status, '|', setR.body.substring(0, 150));
  
  // Check for new cookies
  let newCookie = cookieStr;
  for (const c of setR.cookies) {
    const nm = c.split('=')[0].trim();
    newCookie += '; ' + c.split(';')[0];
  }
  
  // 2. Search on this OTT
  const searches = ott === 'pv' ? ['the boys', 'jack ryan', 'reacher'] :
                   ott === 'dp' ? ['loki', 'mandalorian', 'andor'] :
                   ott === 'hs' ? ['game of thrones', 'the last of us', 'succession'] :
                   ['stranger things', 'squid game', 'wednesday'];
  
  for (const term of searches) {
    const sR = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(term), {
      headers: {'Cookie': newCookie}
    });
    try {
      const p = JSON.parse(sR.body);
      const items = p?.searchResult || [];
      const head = p?.head || '';
      if (items.length > 0 && head !== 'Top Searches') {
        const item = items[0];
        console.log('  "' + term + '" -> id=' + item.id + ' "' + item.t + '"');
      } else {
        console.log('  "' + term + '" -> ' + (head || 'empty') + ' (' + items.length + ')');
      }
    } catch {
      console.log('  "' + term + '" -> not JSON (' + sR.body.length + 'B)');
    }
  }
  console.log('');
}
