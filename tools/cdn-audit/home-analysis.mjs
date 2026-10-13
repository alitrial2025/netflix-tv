#!/usr/bin/env node
/**
 * Analyze net52.cc /mobile/home with correct OS.Gatu UA
 * This page was 16KB — must have OTT categories/genres/tabs
 */
import https from 'https';
import { writeFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';

function req(url, hdrs={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.get(url, {headers:{'User-Agent':GATU,...hdrs},rejectUnauthorized:false,timeout:15000}, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, cookies:[].concat(res.headers['set-cookie']||[])}));
    }).on('error',e=>resolve({status:0,body:e.message,cookies:[]}));
  });
}

console.log('=== net52.cc /mobile/home DEEP ANALYSIS ===\n');
const r = await req('https://net52.cc/mobile/home');
console.log('Status:', r.status, '| Size:', r.body.length + 'B');

await writeFile('tools/cdn-audit/mobile-home.html', r.body);

// Extract ALL JavaScript variables
const varPattern = /var\s+(\w+)\s*=\s*["']([^"']+)["']/g;
let m;
console.log('\nJavaScript variables:');
while ((m = varPattern.exec(r.body)) !== null) {
  console.log('  ' + m[1] + ' = "' + m[2].substring(0, 80) + '"');
}

// Extract all URLs/endpoints
const urlPattern = /["']((?:https?:\/\/[^"'\s]+|\/[a-z][a-z0-9_\/]*\.php[^"']*))["']/g;
const urls = new Set();
while ((m = urlPattern.exec(r.body)) !== null) urls.add(m[1]);
console.log('\nURLs/Endpoints (' + urls.size + '):');
for (const u of [...urls].sort()) console.log('  ' + u.substring(0, 120));

// Extract AJAX calls
const ajaxPattern = /\$\.ajax\s*\(\s*\{([^}]+)\}/g;
console.log('\nAJAX calls:');
while ((m = ajaxPattern.exec(r.body)) !== null) {
  console.log('  ' + m[1].replace(/\s+/g, ' ').substring(0, 200));
}

// Fetch patterns
const fetchPattern = /fetch\s*\(\s*["']([^"']+)["']/g;
while ((m = fetchPattern.exec(r.body)) !== null) {
  console.log('  fetch: ' + m[1]);
}

// XHR patterns
const xhrPattern = /\.open\s*\(\s*["'](\w+)["']\s*,\s*["']([^"']+)["']/g;
while ((m = xhrPattern.exec(r.body)) !== null) {
  console.log('  XHR: ' + m[1] + ' ' + m[2]);
}

// Look for OTT/category references
console.log('\nOTT/Category mentions:');
const ottPattern = /prime|amazon|disney|hotstar|hbo|apple|hulu|paramount|peacock|zee5|sonyliv|jiocinema|altbalaji|voot|mxplayer/gi;
const ottMatches = r.body.match(ottPattern);
if (ottMatches) {
  console.log('  Found:', [...new Set(ottMatches.map(m=>m.toLowerCase()))].join(', '));
} else {
  console.log('  None found');
}

// Look for category/genre/tab structures
const catPattern = /(?:category|categories|genre|genres|tab|tabs|section|sections|menu|platform|source|ott)\s*[=:]\s*[\[{"][^;]{1,500}/gi;
const cats = r.body.match(catPattern);
if (cats) {
  console.log('\nCategory structures:');
  for (const c of cats.slice(0,10)) console.log('  ' + c.substring(0,200));
}

// Look for "post.php" call context  
const postIdx = r.body.indexOf('post.php');
if (postIdx >= 0) {
  console.log('\npost.php context:');
  console.log(r.body.substring(Math.max(0,postIdx-200), Math.min(r.body.length, postIdx+200)).replace(/\n/g,' '));
}

// Look for "search.php" call context
const searchIdx = r.body.indexOf('search.php');
if (searchIdx >= 0) {
  console.log('\nsearch.php context:');
  console.log(r.body.substring(Math.max(0,searchIdx-200), Math.min(r.body.length, searchIdx+200)).replace(/\n/g,' '));
}

console.log('\nSaved full HTML to tools/cdn-audit/mobile-home.html');
