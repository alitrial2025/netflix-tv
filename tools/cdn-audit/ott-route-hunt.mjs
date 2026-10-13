#!/usr/bin/env node
/**
 * Hunt for other OTT routes on net52.cc
 * - Try different API paths, query params, and content types
 * - Check if there's a separate catalog/browse endpoint
 * - Try different "type" or "ott" params on search and post
 */
import https from 'https';
import { URL } from 'url';
import { readFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/133.0 Mobile Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const session = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json','utf8'));
const cookieStr = 'addhash=' + session.addhashEncoded + '; t_hash_t=' + session.tHashTEncoded + '; lang=eng';
const BASE = session.baseUrl;
const XHR = {'X-Requested-With':'XMLHttpRequest'};
const FORM = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};

console.log('=== HUNTING FOR OTHER OTT ROUTES ===\n');

// 1. Check the /mobile/home page for OTT categories, tabs, or links
console.log('--- 1. /mobile/home page analysis ---');
try {
  const r = await req(BASE + '/mobile/home', {headers: {'Cookie': cookieStr}});
  console.log('Status:', r.status, '| Size:', r.body.length + 'B');
  
  // Look for category/OTT references
  const patterns = [
    /prime|amazon|disney|hbo|apple|hulu|paramount|peacock|showtime/gi,
    /category|type|ott|platform|source/gi,
    /tab[s]?|section|genre|catalog/gi,
  ];
  for (const p of patterns) {
    const matches = r.body.match(p);
    if (matches) console.log('  Pattern', p.source.substring(0,30) + ':', [...new Set(matches)].join(', '));
  }
  
  // Look for URLs/endpoints
  const urlPattern = /["'](\/[a-z]+\/[a-z]+\.php[^"']*|https?:\/\/[^"'\s]+)["']/g;
  const urls = new Set();
  let m;
  while ((m = urlPattern.exec(r.body)) !== null) urls.add(m[1]);
  console.log('  Endpoints found:', urls.size);
  for (const u of urls) console.log('    ' + u.substring(0, 100));
} catch(e) { console.log('Error:', e.message); }

// 2. Try search with type/category params
console.log('\n--- 2. Search with category/type params ---');
const searchVariants = [
  '/mobile/search.php?s=the+boys&type=prime',
  '/mobile/search.php?s=the+boys&ott=prime',
  '/mobile/search.php?s=the+boys&cat=prime',
  '/mobile/search.php?s=the+boys&source=prime',
  '/mobile/search.php?s=the+boys&platform=prime',
  '/mobile/search.php?s=loki&type=disney',
  '/mobile/search.php?s=game+of+thrones&type=hbo',
  '/search.php?s=the+boys&type=prime',
  '/search.php?s=game+of+thrones&type=hbo',
  '/search.php?s=loki&type=disney',
];
for (const path of searchVariants) {
  try {
    const r = await req(BASE + path, {headers: {...XHR, 'Cookie': cookieStr}});
    const parsed = JSON.parse(r.body);
    const items = parsed?.searchResult || [];
    const head = parsed?.head || '';
    if (items.length > 0 && head !== 'Top Searches') {
      console.log(path.substring(0,60) + ' -> ' + items.length + ' results: "' + items[0].t + '"');
    }
  } catch {}
}

// 3. Try different API paths
console.log('\n--- 3. Alternative API paths ---');
const apiPaths = [
  '/mobile/browse.php',
  '/mobile/catalog.php',
  '/mobile/home.php',
  '/mobile/list.php',
  '/mobile/genre.php',
  '/mobile/category.php',
  '/mobile/trending.php',
  '/mobile/popular.php',
  '/mobile/new.php',
  '/mobile/discover.php',
  '/api.php',
  '/mobile/api.php',
  '/api/v1/search',
  '/api/v1/browse',
  '/mobile/config.php',
  '/mobile/settings.php',
];
for (const path of apiPaths) {
  try {
    const r = await req(BASE + path, {headers: {...XHR, 'Cookie': cookieStr}});
    if (r.status === 200 && r.body.length > 20) {
      const preview = r.body.substring(0, 150).replace(/\n/g, ' ');
      console.log(path + ': ' + r.status + ' | ' + r.body.length + 'B | ' + preview);
    }
  } catch {}
}

// 4. Try post.php with genre/type params to get different content
console.log('\n--- 4. post.php/browse with genre/type params ---');
const postVariants = [
  {body: 'genre=prime', desc: 'genre=prime'},
  {body: 'genre=disney', desc: 'genre=disney'},
  {body: 'genre=hbo', desc: 'genre=hbo'},
  {body: 'type=prime', desc: 'type=prime'},
  {body: 'type=tv', desc: 'type=tv'},
  {body: 'type=movie', desc: 'type=movie'},
  {body: 'cat=all', desc: 'cat=all'},
  {body: 'list=trending', desc: 'list=trending'},
  {body: 'list=new', desc: 'list=new'},
  {body: 'action=browse', desc: 'action=browse'},
  {body: 'action=list', desc: 'action=list'},
  {body: 'genre=83', desc: 'genre=83 (NF action)'},
  {body: 'genre=10749', desc: 'genre=10749 (NF romance)'},
];
for (const {body, desc} of postVariants) {
  try {
    const r = await req(BASE + '/mobile/post.php', {
      method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body: body
    });
    if (r.body !== '{"status":"n","error":"Invalid User"}' && r.body.length > 20) {
      console.log(desc + ': ' + r.body.length + 'B | ' + r.body.substring(0,150));
    }
  } catch {}
}

// 5. Check the /mobile/home body for genre/browse/list endpoints
console.log('\n--- 5. Genre/Browse/List discovery from home page JS ---');
try {
  const r = await req(BASE + '/mobile/home', {headers: {'Cookie': cookieStr}});
  
  // Extract all PHP endpoints referenced in JS
  const phpPattern = /["']((?:\/mobile\/)?[a-z_]+\.php)["']/g;
  const phps = new Set();
  while ((m = phpPattern.exec(r.body)) !== null) phps.add(m[1]);
  console.log('PHP endpoints in home page:');
  for (const p of phps) console.log('  ' + p);
  
  // Extract all fetch/ajax URLs
  const ajaxPattern = /url\s*:\s*["']([^"']+)["']/g;
  const ajaxUrls = new Set();
  while ((m = ajaxPattern.exec(r.body)) !== null) ajaxUrls.add(m[1]);
  if (ajaxUrls.size > 0) {
    console.log('AJAX URLs:');
    for (const u of ajaxUrls) console.log('  ' + u);
  }
  
  // Look for genre lists or category arrays
  const genrePattern = /genre[s]?\s*[=:]\s*[\[{]([^\]}>]+)[\]}]/gi;
  const genres = r.body.match(genrePattern);
  if (genres) {
    console.log('Genre patterns:');
    for (const g of genres.slice(0,5)) console.log('  ' + g.substring(0,150));
  }
} catch(e) { console.log('Error:', e.message); }

// 6. Try different known mirror domains
console.log('\n--- 6. Other mirror domains ---');
const domains = [
  'https://net77.cc',
  'https://net88.cc',
  'https://net99.cc',
  'https://net55.cc', 
  'https://net66.cc',
  'https://net11.cc',
  'https://net22.cc',
  'https://net33.cc',
  'https://net44.cc',
  'https://iosmirror.cc',
  'https://nfmirror.cc',
];
for (const domain of domains) {
  try {
    const r = await req(domain + '/mobile/search.php?s=the+boys', {headers: XHR});
    const parsed = JSON.parse(r.body);
    const items = parsed?.searchResult || [];
    console.log(domain + ': ' + r.status + ' | ' + items.length + ' results | ' + r.body.substring(0,80));
  } catch(e) {
    try {
      const r = await req(domain + '/', {headers: {}});
      console.log(domain + ': ' + r.status + ' | ' + r.body.length + 'B (homepage)');
    } catch(e2) {
      console.log(domain + ': ' + e2.message.substring(0,50));
    }
  }
}

console.log('\n=== DONE ===');
