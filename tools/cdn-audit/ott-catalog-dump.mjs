#!/usr/bin/env node
/**
 * ═══════════════════════════════════════════════════════════════
 *  HS CATALOG DUMP — Download ALL JioHotstar IDs from net52
 * ═══════════════════════════════════════════════════════════════
 * 
 * 1. Full handshake (with extended polling)
 * 2. Switch to each OTT (hs, dp, pv)
 * 3. Scrape series + movies browse pages
 * 4. Call post.php for every show → get seasons + episodes
 * 5. Save complete catalog to JSON
 */
import https from 'https';
import { writeFile, readFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';
const BASE = 'https://net52.cc';
const FORM = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':GATU, ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

// ═══════════════════════════════════════
// STAGE 1: HANDSHAKE
// ═══════════════════════════════════════
console.log('╔══════════════════════════════════════════════════╗');
console.log('║  HS CATALOG DUMP — Full OTT ID Download         ║');
console.log('╚══════════════════════════════════════════════════╝\n');

console.log('=== STAGE 1: HANDSHAKE ===');

// Try loading existing session first
let cookieStr = '';
try {
  const sessions = ['cdn-diagnosis-iPkTf1'];
  for (const s of sessions) {
    try {
      const data = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\' + s + '\\session.json', 'utf8'));
      cookieStr = 'addhash=' + data.addhashEncoded + '; t_hash_t=' + data.tHashTEncoded + '; lang=eng';
      console.log('Loaded existing session from ' + s);
      break;
    } catch {}
  }
} catch {}

// Check if session is still valid
if (cookieStr) {
  const check = await req(BASE + '/mobile/home?app=1', {headers:{'Cookie':cookieStr}});
  if (check.body.length > 50000) {
    console.log('✅ Existing session valid (' + check.body.length + 'B)');
  } else {
    console.log('Session expired, need fresh handshake...');
    cookieStr = '';
  }
}

if (!cookieStr) {
  // Full handshake
  const homeR = await req(BASE + '/mobile/home?app=1');
  let addhash = '';
  for (const c of homeR.cookies) {
    const m = c.match(/addhash=([^;]+)/);
    if (m) { addhash = decodeURIComponent(m[1]); break; }
  }
  if (!addhash) {
    const bm = homeR.body.match(/data-addhash="REDACTED_EXPIRED_SESSION"]+)"/);
    if (bm) addhash = bm[1];
  }
  console.log('addhash: ' + (addhash ? addhash.substring(0,40) + '...' : 'NOT FOUND'));
  if (!addhash) { console.log('FATAL: no addhash'); process.exit(1); }

  const encoded = encodeURIComponent(addhash);
  const quryM = homeR.body.match(/var\s+Qury\s*=\s*["']([^"']+)["']/);
  const vsiteM = homeR.body.match(/(?:var\s+)?Vsite2?\s*=\s*["']([^"']+)["']/);
  const qury = quryM ? quryM[1] : 'ffr455';
  const vsite = vsiteM ? vsiteM[1] : 'userver';

  // Trigger userver
  console.log('Triggering ' + vsite + '.net52.cc...');
  try { await req('https://' + vsite + '.net52.cc/?' + qury + '=' + encoded + '&a=y&t=' + Math.random()); } catch {}

  // Extended polling — 60 attempts over ~90 seconds
  console.log('Polling verify (up to 90s)...');
  let tHashT = '';
  let lastStatus = '';
  for (let i = 0; i < 60; i++) {
    await sleep(1500);
    try {
      const vR = await req(BASE + '/mobile/verify2.php', {
        method:'POST', headers:{...FORM, 'Cookie':'addhash='+encoded},
        body: 'verify=' + encoded
      });
      for (const c of vR.cookies) {
        const m = c.match(/t_hash_t=([^;]+)/);
        if (m) { tHashT = m[1]; break; }
      }
      try { lastStatus = JSON.parse(vR.body).statusup || ''; } catch {}
      
      if (tHashT) {
        console.log('✅ t_hash_t on attempt ' + (i+1) + ' (' + ((i+1)*1.5).toFixed(0) + 's) status=' + lastStatus);
        break;
      }
      if (i % 10 === 9) console.log('  attempt ' + (i+1) + ': ' + lastStatus);
    } catch {}
  }

  if (!tHashT) { console.log('FATAL: no t_hash_t'); process.exit(1); }
  cookieStr = 'addhash=' + encoded + '; t_hash_t=' + tHashT + '; lang=eng';

  // Wait a moment then verify session works
  await sleep(2000);
  const verify = await req(BASE + '/mobile/home?app=1', {headers:{'Cookie':cookieStr}});
  console.log('Session check: ' + verify.body.length + 'B');
  if (verify.body.length < 50000) {
    console.log('⚠️ Session may not be fully activated (ad not watched).');
    console.log('The script will try anyway — some endpoints work with partial session.');
  }
}

// ═══════════════════════════════════════
// STAGE 2: DUMP EACH OTT
// ═══════════════════════════════════════
const catalog = {};

for (const ott of ['hs', 'pv', 'dp']) {
  console.log('\n\n=== STAGE 2: DUMPING ' + ott.toUpperCase() + ' ===');
  
  // Switch OTT
  const setR = await req(BASE + '/mobile/setting.php', {
    method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'ott=' + ott
  });
  
  // Update cookie with ott
  const fullCookie = cookieStr + '; ott=' + ott;
  console.log('Switched to ' + ott + ': ' + setR.body.trim());

  const ottData = { shows: [], movies: [] };
  
  for (const page of ['series', 'movies']) {
    const pageR = await req(BASE + '/mobile/' + page + '?app=1', {
      headers: {'Cookie': fullCookie, 'X-Requested-With': 'app.netmirror.netmirrornew'}
    });
    console.log('\n/' + page + ': ' + pageR.body.length + 'B');
    
    if (pageR.body.length < 20000) {
      console.log('  ⚠️ Got ad gate, skipping...');
      continue;
    }

    // Extract poster IDs from data-post attributes
    const postRe = /data-post="([^"]+)"/g;
    const ids = new Set();
    let m;
    while ((m = postRe.exec(pageR.body)) !== null) ids.add(m[1]);
    
    // Also extract from imgcdn URLs
    if (ott === 'pv') {
      const pvIdRe = /imgcdn[^"]*\/pv\/\d+\/([A-Z0-9]{26})\./g;
      while ((m = pvIdRe.exec(pageR.body)) !== null) ids.add(m[1]);
    }
    
    // Also from poster/nf paths
    const posterIdRe = /imgcdn[^"]*\/(?:poster|nf|pv|hs)\/\d+\/([^."]+)\./g;
    while ((m = posterIdRe.exec(pageR.body)) !== null) ids.add(m[1]);
    
    console.log('  Found ' + ids.size + ' content IDs');
    
    // Get details for each ID via post.php
    const items = [];
    const idList = [...ids];
    
    for (let i = 0; i < idList.length; i++) {
      const id = idList[i];
      const ts = Math.floor(Date.now()/1000);
      
      const postPath = ott === 'nf' ? '/mobile/post.php' : '/mobile/' + ott + '/post.php';
      const pR = await req(BASE + postPath + '?id=' + id + '&t=' + ts, {
        headers: {'Cookie': fullCookie, 'X-Requested-With':'XMLHttpRequest'}
      });
      
      try {
        const data = JSON.parse(pR.body);
        if (data.status === 'n') continue;
        
        const seasons = [];
        const episodes = data.episodes || [];
        
        // Group episodes by season
        const bySeason = {};
        for (const ep of episodes) {
          const s = ep.s || 'S1';
          if (!bySeason[s]) bySeason[s] = [];
          bySeason[s].push({
            id: ep.id,
            title: ep.t,
            season: s,
            episode: ep.ep,
            duration: ep.time
          });
        }
        
        const item = {
          id: id,
          title: data.t || '',
          desc: (data.desc || '').substring(0, 200),
          cast: data.cast || '',
          director: data.director || '',
          type: page === 'series' ? 'series' : 'movie',
          seasons: bySeason,
          seasonCount: Object.keys(bySeason).length,
          episodeCount: episodes.length,
          lang: data.d_lang || ''
        };
        
        items.push(item);
        
        if ((i+1) % 20 === 0) console.log('  Processed ' + (i+1) + '/' + idList.length);
      } catch {}
      
      // Small delay to be polite
      if (i % 5 === 4) await sleep(200);
    }
    
    console.log('  Got details for ' + items.length + ' titles');
    if (page === 'series') ottData.shows = items;
    else ottData.movies = items;
  }
  
  catalog[ott] = ottData;
}

// Switch back to nf
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'ott=nf'
});

// ═══════════════════════════════════════
// STAGE 3: SAVE CATALOG
// ═══════════════════════════════════════
console.log('\n\n=== STAGE 3: SAVE CATALOG ===');

const outputPath = 'd:\\Netflixtv\\tools\\cdn-audit\\ott-catalog.json';
await writeFile(outputPath, JSON.stringify(catalog, null, 2));
console.log('Saved to ' + outputPath);

// Summary
for (const [ott, data] of Object.entries(catalog)) {
  const showCount = data.shows?.length || 0;
  const movieCount = data.movies?.length || 0;
  const totalEps = data.shows?.reduce((sum, s) => sum + s.episodeCount, 0) || 0;
  console.log('[' + ott.toUpperCase() + '] ' + showCount + ' shows (' + totalEps + ' episodes) | ' + movieCount + ' movies');
}

console.log('\n✅ Done!');
