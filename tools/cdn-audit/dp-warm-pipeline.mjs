#!/usr/bin/env node
/**
 * WARM PIPELINE — Disney+ (same approach as the Smallville diagnostic)
 * Full handshake → switch to DP → browse → pick show → post.php → playlist → HLS → CDN → segments
 */
import https from 'https';
import crypto from 'crypto';
import { readFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';
const BASE = 'https://net52.cc';
const FORM = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};
const H1 = '235ca31540ab8d90fcef4a00de8a247c';
const sleep = ms => new Promise(r => setTimeout(r, ms));

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search,
      method: opts.method||'GET',
      headers: {'User-Agent': GATU, 'X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    });
    r.on('error',e=>resolve({status:0,body:e.message,cookies:[]}));
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

console.log('╔══════════════════════════════════════════════════════╗');
console.log('║  WARM PIPELINE — Disney+ on net52                   ║');
console.log('╚══════════════════════════════════════════════════════╝\n');
const t0 = Date.now();

// ═══ 1. SESSION ═══
console.log('=== 1. SESSION ===');
let cookieStr = '';
try {
  const data = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json', 'utf8'));
  cookieStr = 'addhash=' + data.addhashEncoded + '; t_hash_t=' + data.tHashTEncoded + '; lang=eng';
  console.log('✅ Loaded session');
} catch {
  console.log('No session — running fresh handshake...');
  const homeR = await req(BASE + '/mobile/home?app=1');
  let addhash = '';
  for (const c of homeR.cookies) { const m=c.match(/addhash=([^;]+)/); if(m){addhash=decodeURIComponent(m[1]);break;} }
  if (!addhash) { const bm=homeR.body.match(/data-addhash="REDACTED_EXPIRED_SESSION"]+)"/); if(bm) addhash=bm[1]; }
  console.log('addhash: ' + (addhash?addhash.substring(0,40)+'...':'NOT FOUND'));
  if (!addhash) { console.log('FATAL'); process.exit(1); }
  const enc = encodeURIComponent(addhash);
  const quryM = homeR.body.match(/var\s+Qury\s*=\s*["']([^"']+)["']/);
  const vsiteM = homeR.body.match(/(?:var\s+)?Vsite2?\s*=\s*["']([^"']+)["']/);
  try { await req('https://'+(vsiteM?vsiteM[1]:'userver')+'.net52.cc/?'+(quryM?quryM[1]:'ffr455')+'='+enc+'&a=y&t='+Math.random()); } catch {}
  let tHashT = '';
  for (let i=0;i<60;i++){
    await sleep(1500);
    const vR=await req(BASE+'/mobile/verify2.php',{method:'POST',headers:{...FORM,'Cookie':'addhash='+enc},body:'verify='+enc});
    for(const c of vR.cookies){const m=c.match(/t_hash_t=([^;]+)/);if(m){tHashT=m[1];break;}}
    if(tHashT){console.log('✅ t_hash_t at '+(i+1)+' ('+(((i+1)*1.5)|0)+'s)');break;}
    if(i%10===9)console.log('  polling...');
  }
  if(!tHashT){console.log('FATAL: no t_hash_t');process.exit(1);}
  cookieStr='addhash='+enc+'; t_hash_t='+tHashT+'; lang=eng';
}

// Verify session
const checkR = await req(BASE + '/mobile/home?app=1', {headers:{'Cookie':cookieStr}});
console.log('Session: ' + checkR.body.length + 'B ' + (checkR.body.length>50000?'✅':'⚠️'));

// ═══ 2. SWITCH TO DP ═══
console.log('\n=== 2. SWITCH TO DP ===');
await req(BASE + '/mobile/setting.php', {method:'POST', headers:{...FORM, 'Cookie':cookieStr}, body:'ott=dp'});
const dpCookie = cookieStr + '; ott=dp';
console.log('Switched to DP');

// ═══ 3. BROWSE DP CATALOG ═══
console.log('\n=== 3. BROWSE DP ===');
const seriesR = await req(BASE + '/mobile/series?app=1', {headers:{'Cookie':dpCookie}});
const moviesR = await req(BASE + '/mobile/movies?app=1', {headers:{'Cookie':dpCookie}});
console.log('Series: ' + seriesR.body.length + 'B | Movies: ' + moviesR.body.length + 'B');

// Extract IDs
const postRe = /data-post="(\d+)"/g;
let m;
const seriesIds = new Set(), movieIds = new Set();
while ((m = postRe.exec(seriesR.body)) !== null) seriesIds.add(m[1]);
const postRe2 = /data-post="(\d+)"/g;
while ((m = postRe2.exec(moviesR.body)) !== null) movieIds.add(m[1]);
console.log('Series IDs: ' + seriesIds.size + ' | Movie IDs: ' + movieIds.size);

// ═══ 4. FETCH SHOW DETAILS (post.php with DP cookie) ═══
console.log('\n=== 4. POST.PHP (first 5 series + 5 movies) ===');
const ts = Math.floor(Date.now()/1000);
const allContent = [];

for (const [label, ids] of [['SERIES', [...seriesIds].slice(0,5)], ['MOVIES', [...movieIds].slice(0,5)]]) {
  for (const id of ids) {
    const r = await req(BASE + '/mobile/post.php?id=' + id + '&t=' + ts, {headers:{'Cookie':dpCookie}});
    try {
      const d = JSON.parse(r.body);
      const eps = (d.episodes||[]).filter(Boolean);
      const title = d.t || d.title || d.desc?.substring(0,40) || '(unknown)';
      console.log('[' + label + '] id=' + id + ' | ' + title + ' | ' + eps.length + ' eps');
      if (eps.length > 0) {
        console.log('  E1: id=' + eps[0].id + ' "' + eps[0].t + '" ' + eps[0].s + eps[0].ep);
      }
      allContent.push({id, title, episodes: eps, type: label});
    } catch {
      console.log('[' + label + '] id=' + id + ': ' + r.body.substring(0,60));
    }
  }
}

// ═══ 5. PLAYLIST + HLS FOR DP CONTENT ═══
console.log('\n=== 5. PLAYLIST → HLS → CDN ===');

// Try both show IDs and episode IDs
const testIds = [];
for (const c of allContent) {
  testIds.push({id: c.id, label: c.title});
  if (c.episodes.length > 0 && c.episodes[0]?.id) {
    testIds.push({id: c.episodes[0].id, label: c.title + ' ep'});
  }
}

for (const test of testIds.slice(0, 8)) {
  console.log('\n--- ' + test.label + ' (id=' + test.id + ') ---');
  
  // Playlist WITH cookie
  const plR = await req(BASE + '/mobile/playlist.php?id=' + test.id + '&t=Test&tm=' + ts + '&lang=eng&hd=on&userhash=' + encodeURIComponent(cookieStr.split('t_hash_t=')[1]?.split(';')[0]||''), {headers:{'Cookie':dpCookie}});
  try {
    const pl = JSON.parse(plR.body);
    const file = pl[0]?.sources?.[0]?.file;
    const subs = pl[0]?.tracks?.length || 0;
    console.log('  Playlist: ' + (file ? '✅ HLS ' + subs + ' subs' : '❌'));
    if (file) console.log('    ' + file.substring(0,100));
  } catch { console.log('  Playlist: ' + plR.body.substring(0,60)); }
  
  // HLS WITH cookie
  const h2 = crypto.createHash('md5').update(ts.toString() + test.id).digest('hex');
  const token = H1 + '::' + h2 + '::' + ts + '::ek::m';
  const hlsR = await req(BASE + '/mobile/hls/' + test.id + '.m3u8?in=' + encodeURIComponent(token) + '&hd=on&lang=eng', {headers:{'Cookie':dpCookie}});
  
  const cdnUrls = hlsR.body.match(/https?:\/\/[^\s"]+/g) || [];
  const validCdn = cdnUrls.filter(u => !u.includes('https:///'));
  const emptyCdn = cdnUrls.filter(u => u.includes('https:///'));
  
  if (validCdn.length > 0) {
    console.log('  HLS: ✅ ' + hlsR.body.length + 'B | CDN: ' + validCdn[0].substring(0,80));
    // Try downloading first segment
    const segR = await req(validCdn[0]);
    console.log('  CDN segment: ' + segR.status + ' | ' + segR.body.length + 'B');
  } else if (emptyCdn.length > 0) {
    console.log('  HLS: ⚠️ ' + hlsR.body.length + 'B | CDN hostname EMPTY');
    console.log('    ' + emptyCdn[0].substring(0,80));
  } else {
    console.log('  HLS: ' + hlsR.body.length + 'B');
    if (hlsR.body.length < 200) console.log('    ' + hlsR.body.replace(/\n/g,' '));
  }
}

// Switch back to NF
await req(BASE + '/mobile/setting.php', {method:'POST', headers:{...FORM, 'Cookie':cookieStr}, body:'ott=nf'});

console.log('\n\nTotal: ' + ((Date.now()-t0)/1000).toFixed(1) + 's');
