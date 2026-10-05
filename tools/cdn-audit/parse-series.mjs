#!/usr/bin/env node
import https from 'https';

const r = await new Promise((resolve,reject) => {
  https.get('https://net52.cc/series.php?id=70155584', {headers:{'User-Agent':'Mozilla/5.0'},rejectUnauthorized:false}, res => {
    let d=''; res.on('data',c=>d+=c); res.on('end',()=>resolve(d));
  }).on('error',reject);
});

console.log('Total bytes:', r.length);
console.log('');

// Find all 7-9 digit IDs
const idPattern = /['"](\d{7,9})['"]/g;
const ids = new Set();
let m;
while ((m = idPattern.exec(r)) !== null) ids.add(m[1]);
console.log('Unique IDs found:', ids.size);
for (const id of ids) console.log('  ', id);

console.log('');

// Look for "season" keyword context
let pos = 0;
let count = 0;
while ((pos = r.indexOf('eason', pos)) >= 0 && count < 15) {
  const start = Math.max(0, pos - 80);
  const end = Math.min(r.length, pos + 80);
  const context = r.substring(start, end).replace(/\n/g, ' ').replace(/\r/g, '');
  console.log('season context #' + (count+1) + ':', context);
  pos += 5;
  count++;
}

console.log('');

// Look for onclick handlers
const onclickPattern = /onclick\s*=\s*["'][^"']{5,200}["']/gi;
const onclicks = r.match(onclickPattern) || [];
console.log('onclick handlers:', onclicks.length);
for (const oc of onclicks) console.log('  ', oc.substring(0, 150));

console.log('');

// Look for data- attributes  
const dataPattern = /data-[\w-]+\s*=\s*["'][^"']+["']/gi;
const dataAttrs = r.match(dataPattern) || [];
console.log('data- attributes:', dataAttrs.length);
for (const da of dataAttrs) console.log('  ', da.substring(0, 150));

console.log('');

// Show <select> or <option> tags (season dropdowns)
const selectPattern = /<(?:select|option)[^>]*>/gi;
const selects = r.match(selectPattern) || [];
console.log('select/option tags:', selects.length);
for (const s of selects) console.log('  ', s.substring(0, 150));

console.log('');

// Show <a> links
const linkPattern = /<a\s[^>]*href\s*=\s*["'][^"']*["'][^>]*>/gi;
const links = r.match(linkPattern) || [];
console.log('Links:', links.length);
for (const l of links.slice(0, 20)) console.log('  ', l.substring(0, 150));

// Dump the full body to file for inspection
import { writeFile } from 'fs/promises';
await writeFile('tools/cdn-audit/series-page.html', r);
console.log('\nFull HTML saved to tools/cdn-audit/series-page.html');
