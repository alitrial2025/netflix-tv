#!/usr/bin/env node
/**
 * DP FULL CATALOG DUMP + ZERO-COOKIE CHAIN TEST
 * DP uses /mobile/ (same as NF) with ott=dp cookie for post.php
 * episodes.php, playlist.php, hls all work WITHOUT cookies
 */
import https from 'https';
import crypto from 'crypto';
import { readFile, writeFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64) AppleWebKit/537.36 /OS.Gatu v3.0',
        'Accept':'*/*','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    }).on('error',e=>resolve({status:0,body:e.message,cookies:[]})).end(opts.body||undefined);
  });
}

const BASE = 'https://net52.cc';
const FORM = {'Content-Type':'application/x-www-form-urlencoded'};
const H1 = '235ca31540ab8d90fcef4a00de8a247c';
const sleep = ms => new Promise(r => setTimeout(r, ms));

// Load session
let cookieStr = '';
try {
  const data = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json', 'utf8'));
  cookieStr = 'addhash=' + data.addhashEncoded + '; t_hash_t=' + data.tHashTEncoded + '; lang=eng';
} catch { console.log('No session'); process.exit(1); }

console.log('╔══════════════════════════════════════════════════╗');
console.log('║  DISNEY+ FULL DUMP + ZERO-COOKIE CHAIN          ║');
console.log('╚══════════════════════════════════════════════════╝\n');

// Switch to DP
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie':cookieStr}, body:'ott=dp'
});
const dpCookie = cookieStr + '; ott=dp';

// Get both pages
const dpCatalog = { shows: [], movies: [] };

for (const page of ['series', 'movies']) {
  const pageR = await req(BASE + '/mobile/' + page + '?app=1', {headers:{'Cookie':dpCookie}});
  console.log('/' + page + ': ' + pageR.body.length + 'B');
  
  const postRe = /data-post="([^"]+)"/g;
  const ids = new Set();
  let m;
  while ((m = postRe.exec(pageR.body)) !== null) ids.add(m[1]);
  console.log('  IDs: ' + ids.size);
  
  const idList = [...ids].filter(id => id !== '+post_id+');
  
  // Call post.php for each (needs ott=dp cookie)
  for (let i = 0; i < idList.length; i++) {
    const id = idList[i];
    const ts = Math.floor(Date.now()/1000);
    const r = await req(BASE + '/mobile/post.php?id=' + id + '&t=' + ts, {
      headers: {'Cookie': dpCookie}
    });
    try {
      const d = JSON.parse(r.body);
      if (d.status === 'n') continue;
      
      const episodes = d.episodes || [];
      const bySeason = {};
      for (const ep of episodes) {
        const s = ep.s || 'S1';
        if (!bySeason[s]) bySeason[s] = [];
        bySeason[s].push({id: ep.id, title: ep.t, season: s, episode: ep.ep, duration: ep.time});
      }
      
      const item = {
        id, title: d.t || d.title || '', desc: (d.desc||'').substring(0,200),
        cast: d.cast||'', director: d.director||'', type: page==='series'?'series':'movie',
        seasons: bySeason, seasonCount: Object.keys(bySeason).length,
        episodeCount: episodes.length, lang: d.d_lang||''
      };
      
      if (page === 'series') dpCatalog.shows.push(item);
      else dpCatalog.movies.push(item);
    } catch {}
    
    if (i % 5 === 4) await sleep(200);
    if ((i+1) % 30 === 0) console.log('  Processed ' + (i+1) + '/' + idList.length);
  }
  
  console.log('  Got: ' + (page==='series' ? dpCatalog.shows.length : dpCatalog.movies.length) + ' titles');
}

// Save DP catalog
const existingCatalog = JSON.parse(await readFile('tools/cdn-audit/ott-catalog.json','utf8'));
existingCatalog.dp = dpCatalog;
await writeFile('tools/cdn-audit/ott-catalog.json', JSON.stringify(existingCatalog, null, 2));
console.log('\n✅ DP catalog saved to ott-catalog.json');

// Summary
const totalEps = dpCatalog.shows.reduce((s,x)=>s+x.episodeCount, 0);
console.log('[DP] ' + dpCatalog.shows.length + ' shows (' + totalEps + ' eps) | ' + dpCatalog.movies.length + ' movies');

// === ZERO-COOKIE CHAIN TEST ===
console.log('\n=== ZERO-COOKIE CHAIN TEST (NO cookies) ===\n');

const testShows = dpCatalog.shows.filter(s => s.episodeCount > 0).slice(0, 5);
for (const show of testShows) {
  const firstSeason = Object.keys(show.seasons)[0];
  const eps = show.seasons[firstSeason];
  if (!eps || !eps.length) continue;
  
  const epId = eps[0].id;
  console.log('Show=' + show.id + ' ' + (show.title||show.desc.substring(0,30)) + ' | ' + firstSeason + ' ep=' + epId);
  
  // episodes.php NO cookie
  const epR = await req(BASE + '/mobile/episodes.php?s=' + show.id + '&p=0');
  try {
    const p = JSON.parse(epR.body);
    const episodes = p?.episodes || [];
    console.log('  episodes.php: ' + (episodes.length > 0 ? '✅ '+episodes.length+' eps' : '⚠️ empty (nextPage='+p?.nextPageSeason+')'));
  } catch {}
  
  // playlist.php NO cookie
  const plR = await req(BASE + '/mobile/playlist.php?id=' + epId);
  try {
    const pl = JSON.parse(plR.body);
    const file = pl[0]?.sources?.[0]?.file;
    console.log('  playlist.php: ' + (file ? '✅ HLS found' : '❌'));
    if (file) console.log('    ' + file.substring(0,80));
  } catch {}
  
  // HLS NO cookie
  const ts = Math.floor(Date.now()/1000).toString();
  const h2 = crypto.createHash('md5').update(ts + epId).digest('hex');
  const token = H1 + '::' + h2 + '::' + ts + '::ek::m';
  const hlsR = await req(BASE + '/mobile/hls/' + epId + '.m3u8?in=' + encodeURIComponent(token));
  const hasCdn = hlsR.body.includes('.top/') || hlsR.body.includes('cdn');
  console.log('  HLS: ' + hlsR.body.length + 'B | cdn=' + hasCdn);
  if (hlsR.body.length > 30 && hlsR.body.length < 500) {
    // Check if CDN hostname is missing
    const emptyHost = hlsR.body.includes('https:///');
    if (emptyHost) console.log('  ⚠️ CDN hostname empty — needs fix');
    console.log('  ' + hlsR.body.split('\n').filter(l=>l.includes('http')).join('\n  ').substring(0,200));
  }
  console.log('');
}

// Switch back to NF
await req(BASE + '/mobile/setting.php', {
  method:'POST', headers:{...FORM, 'Cookie':cookieStr}, body:'ott=nf'
});
