#!/usr/bin/env node
/**
 * DP deep chain test:
 * - post.php returns null metadata but status:"y" 
 * - HLS returned M3U8 but CDN hostname was empty
 * - Let's check playlist.php, episodes.php, and the page HTML for real titles
 */
import https from 'https';
import crypto from 'crypto';
import { readFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0','X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end();
  });
}

const BASE = 'https://net52.cc';
const H1 = '235ca31540ab8d90fcef4a00de8a247c';

console.log('╔══════════════════════════════════════════════╗');
console.log('║  DP DEEP CHAIN ANALYSIS                      ║');
console.log('╚══════════════════════════════════════════════╝\n');

// Read the saved DP page
const dpHtml = await readFile('tools/cdn-audit/dp-series-page.html', 'utf8');

// Extract ALL poster data — look at the HTML structure
console.log('=== 1. DP PAGE HTML ANALYSIS ===\n');

// Find poster titles from the page (data-t or title attributes)
const titleRe = /data-post="(\d+)"[^>]*data-t="([^"]+)"/g;
let m;
const dpTitles = [];
while ((m = titleRe.exec(dpHtml)) !== null) {
  dpTitles.push({id: m[1], title: m[2]});
}
console.log('Posters with data-t: ' + dpTitles.length);

// Try alt pattern — title might be in a nearby element
const posterRe = /data-post="(\d+)"/g;
const dpIds = [];
while ((m = posterRe.exec(dpHtml)) !== null) dpIds.push(m[1]);
console.log('Total data-post IDs: ' + dpIds.length);

// Look for titles near posters — search for <p>, <h3>, <span> after data-post
const blockRe = /data-post="(\d+)"[\s\S]{0,500}?(?:class="[^"]*title[^"]*"|<h\d|<p[^>]*>)[\s>]*([^<]{2,50})/gi;
while ((m = blockRe.exec(dpHtml)) !== null) {
  if (!dpTitles.find(t => t.id === m[1])) {
    dpTitles.push({id: m[1], title: m[2].trim()});
  }
}

// Also look for img alt attributes
const imgAltRe = /data-post="(\d+)"[\s\S]{0,300}?alt="([^"]+)"/gi;
while ((m = imgAltRe.exec(dpHtml)) !== null) {
  const existing = dpTitles.find(t => t.id === m[1]);
  if (!existing) dpTitles.push({id: m[1], title: m[2]});
  else if (!existing.title) existing.title = m[2];
}

console.log('Titles extracted: ' + dpTitles.length);
dpTitles.slice(0,10).forEach(t => console.log('  ' + t.id + ': ' + t.title));

// Check what imgcdn path DP uses
const imgPathRe = /imgcdn\.kim\/([^"]+)/g;
const paths = new Set();
while ((m = imgPathRe.exec(dpHtml)) !== null) paths.add(m[1].substring(0,20));
console.log('\nimgcdn paths: ' + [...paths].slice(0,5).join(', '));

// Check data-extra on the body
const bodyRe = /<body[^>]+>/;
const bodyTag = dpHtml.match(bodyRe);
if (bodyTag) console.log('\nbody tag: ' + bodyTag[0].substring(0,200));

// === 2. Test each endpoint with NO cookies ===
console.log('\n=== 2. ZERO-COOKIE CHAIN (10 DP IDs) ===\n');

const uniqueIds = [...new Set(dpIds.filter(id => id !== '+post_id+'))];
const ts = Math.floor(Date.now()/1000);

for (const id of uniqueIds.slice(0, 10)) {
  console.log('ID=' + id + ':');
  
  // episodes.php
  const epR = await req(BASE + '/mobile/episodes.php?s=' + id + '&p=0');
  try {
    const p = JSON.parse(epR.body);
    const eps = p?.episodes || [];
    if (eps.length > 0 && eps[0]) {
      console.log('  episodes: ✅ ' + eps.length + ' | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '" id=' + eps[0].id);
    } else {
      console.log('  episodes: empty (nextPage=' + (p?.nextPageSeason||'') + ')');
    }
  } catch { console.log('  episodes: parse error'); }
  
  // playlist.php (full response)
  const plR = await req(BASE + '/mobile/playlist.php?id=' + id);
  try {
    const pl = JSON.parse(plR.body);
    const file = pl[0]?.sources?.[0]?.file || '';
    const subs = pl[0]?.tracks?.length || 0;
    const img = pl[0]?.image2 || '';
    console.log('  playlist: ' + (file ? '✅ HLS' : '❌ no file') + ' | subs=' + subs + ' | img=' + img.substring(0,50));
    if (file) console.log('    file: ' + file.substring(0,100));
  } catch { console.log('  playlist: ' + plR.body.substring(0,60)); }
  
  // HLS with token
  const h2 = crypto.createHash('md5').update(ts.toString() + id).digest('hex');
  const token = H1 + '::' + h2 + '::' + ts + '::ek::m';
  const hlsR = await req(BASE + '/mobile/hls/' + id + '.m3u8?in=' + encodeURIComponent(token));
  
  if (hlsR.body.length > 30) {
    const cdnUrls = hlsR.body.match(/https?:\/\/[^\s"]+/g) || [];
    const hasValidCdn = cdnUrls.some(u => !u.includes('https:///'));
    const hasEmptyCdn = cdnUrls.some(u => u.includes('https:///'));
    console.log('  HLS: ' + hlsR.body.length + 'B | validCDN=' + hasValidCdn + ' | emptyCDN=' + hasEmptyCdn);
    if (cdnUrls.length) console.log('    CDN: ' + cdnUrls[0].substring(0,80));
  } else {
    console.log('  HLS: ' + hlsR.body.length + 'B (empty)');
  }
  console.log('');
}
