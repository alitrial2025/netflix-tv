#!/usr/bin/env node
import https from 'https';
import { readFile, writeFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';

function req(url, hdrs={}) {
  return new Promise((resolve, reject) => {
    https.get(url, {headers:{'User-Agent':GATU,...hdrs},rejectUnauthorized:false,timeout:15000}, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message}));
  });
}

const session = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json','utf8'));
const cookie = 'addhash='+session.addhashEncoded+'; t_hash_t='+session.tHashTEncoded+'; lang=eng';

console.log('=== FULL HOME PAGE (with cookies, 121KB) ===\n');
const r = await req('https://net52.cc/mobile/home', {'Cookie':cookie});
console.log('Size:', r.body.length + 'B');
await writeFile('tools/cdn-audit/home-full.html', r.body);

// Search for OTT mentions
const ottPattern = /prime|amazon|disney|hotstar|hbo|apple|hulu|paramount|peacock|zee5|jiocinema|voot|sonyliv|mx.?player/gi;
const matches = r.body.match(ottPattern) || [];
console.log('\nOTT mentions:', [...new Set(matches.map(m=>m.toLowerCase()))].join(', '));

// Find all genre/category sections
const genrePattern = /genre\w*[=:]["'\s]*([^"'\n;]{2,200})/gi;
let m;
console.log('\nGenre patterns:');
while ((m = genrePattern.exec(r.body)) !== null) {
  console.log('  ' + m[0].substring(0,150));
}

// Find all show IDs and titles in the page
const showPattern = /["']id["']\s*:\s*["'](\d+)["']/g;
const showIds = new Set();
while ((m = showPattern.exec(r.body)) !== null) showIds.add(m[1]);
console.log('\nShow IDs in home page:', showIds.size);

// Find all category/rail/row sections
const sectionPattern = /<[^>]*class="[^"]*(?:genre|rail|row|section|category|slider)[^"]*"[^>]*>/gi;
const sections = r.body.match(sectionPattern) || [];
console.log('\nSection elements:', sections.length);
for (const s of sections.slice(0,10)) console.log('  ' + s.substring(0,150));

// Find all <h2> or heading elements (rail titles)
const headingPattern = /<h[1-6][^>]*>([^<]+)<\/h[1-6]>/gi;
const headings = [];
while ((m = headingPattern.exec(r.body)) !== null) headings.push(m[1].trim());
console.log('\nSection headings:', headings.length);
for (const h of headings) console.log('  ' + h);

// Find PHP endpoints used in the full page
const phpPattern = /["']((?:\/mobile\/)?[a-z_]+\.php[^"']*)["']/g;
const phps = new Set();
while ((m = phpPattern.exec(r.body)) !== null) phps.add(m[1]);
console.log('\nPHP endpoints:');
for (const p of [...phps].sort()) console.log('  ' + p);

// Find all AJAX/fetch calls
const ajaxPattern = /url\s*:\s*["']([^"']+)["']/g;
console.log('\nAJAX URLs:');
while ((m = ajaxPattern.exec(r.body)) !== null) console.log('  ' + m[1]);

console.log('\nSaved to tools/cdn-audit/home-full.html');
