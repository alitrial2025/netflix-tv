#!/usr/bin/env node
/**
 * Parse /series.php?nfid=showId — 14KB response, must have season data
 */
import https from 'https';
import { writeFile } from 'fs/promises';

function req(url) {
  return new Promise((resolve, reject) => {
    https.get(url, {headers:{'User-Agent':'Mozilla/5.0'},rejectUnauthorized:false}, res => {
      let d=''; res.on('data',c=>d+=c); res.on('end',()=>resolve(d));
    }).on('error',reject);
  });
}

const body = await req('https://net52.cc/series.php?nfid=70155584');
console.log('Total bytes:', body.length);
await writeFile('tools/cdn-audit/series-nfid.html', body);

// Extract ALL data- attributes
const dataPattern = /data-[\w_-]+\s*=\s*["']([^"']*)["']/g;
let m;
const dataAttrs = {};
while ((m = dataPattern.exec(body)) !== null) {
  const full = m[0];
  const name = full.match(/data-([\w_-]+)/)[1];
  const val = m[1];
  if (!dataAttrs[name]) dataAttrs[name] = [];
  dataAttrs[name].push(val);
}
console.log('\nData attributes found:');
for (const [name, vals] of Object.entries(dataAttrs)) {
  const unique = [...new Set(vals)];
  console.log('  data-' + name + ': ' + unique.length + ' unique values');
  for (const v of unique.slice(0,10)) console.log('    ' + v);
}

// Extract all numeric IDs (7-9 digits) 
const idPattern = /\b(\d{7,9})\b/g;
const allIds = new Set();
while ((m = idPattern.exec(body)) !== null) allIds.add(m[1]);
console.log('\nAll 7-9 digit IDs:', allIds.size);
for (const id of [...allIds].sort()) console.log('  ' + id);

// Look for "season" keyword
let pos = 0;
let idx = 0;
console.log('\nSeason keyword contexts:');
while ((pos = body.indexOf('eason', pos)) >= 0 && idx < 25) {
  const start = Math.max(0, pos - 100);
  const end = Math.min(body.length, pos + 100);
  console.log('#' + idx + ': ' + body.substring(start, end).replace(/[\n\r]/g, ' ').substring(0, 180));
  pos += 5;
  idx++;
}

// Look for table rows or list items with episode data
const trPattern = /<tr[^>]*>[\s\S]*?<\/tr>/gi;
const trs = body.match(trPattern) || [];
console.log('\nTable rows:', trs.length);
for (const tr of trs.slice(0, 5)) {
  console.log('  ' + tr.replace(/[\n\r]/g, ' ').substring(0, 200));
}

// Look for div elements with class containing "season" or "episode"
const divPattern = /<div[^>]*(?:season|episode|eppost)[^>]*>/gi;
const divs = body.match(divPattern) || [];
console.log('\nSeason/Episode divs:', divs.length);
for (const d of divs.slice(0, 20)) {
  console.log('  ' + d.substring(0, 200));
}

// Look for buttons with season data
const btnPattern = /<button[^>]*data-[^>]*>/gi;
const btns = body.match(btnPattern) || [];
console.log('\nButtons with data:', btns.length);
for (const b of btns.slice(0, 20)) {
  console.log('  ' + b.substring(0, 250));
}

console.log('\nSaved to tools/cdn-audit/series-nfid.html');
