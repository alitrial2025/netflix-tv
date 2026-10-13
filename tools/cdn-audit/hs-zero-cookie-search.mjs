#!/usr/bin/env node
/**
 * HS ZERO-COOKIE SEARCH + SEASON RESOLUTION
 * Goal: Find HS show IDs and season IDs WITHOUT any cookies
 * 
 * Approaches:
 * 1. JioCinema show pages (Next.js __NEXT_DATA__)
 * 2. Hotstar deep links with known ID format
 * 3. Google search for show IDs
 * 4. JioCinema API v2/v3
 * 5. Hotstar content API
 */
import https from 'https';

function req(url, opts={}) {
  return new Promise((resolve) => {
    const u = new URL(url);
    https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept':'text/html,application/json,*/*','Accept-Language':'en-US,en;q=0.9', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
        req(loc, opts).then(resolve); res.resume(); return;
      }
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message})).end();
  });
}

const BASE = 'https://net52.cc';

console.log('╔═══════════════════════════════════════════════════╗');
console.log('║  HS ZERO-COOKIE: Search + Season Resolution       ║');
console.log('╚═══════════════════════════════════════════════════╝\n');

// ═══ 1. Try JioCinema show pages ═══
console.log('=== 1. JIOCINEMA SHOW PAGES ===\n');

const jcUrls = [
  'https://www.jiocinema.com/tv-shows/ironheart/3846612',
  'https://www.jiocinema.com/tv-shows/loki/3698',
  'https://www.jiocinema.com/tv-shows/lanterns/3846612',
  'https://www.jiocinema.com/search/ironheart',
  'https://www.jiocinema.com/search?q=ironheart',
];

for (const url of jcUrls) {
  const r = await req(url);
  console.log(url.substring(url.indexOf('.com/')+4) + ': ' + r.status + ' | ' + r.body.length + 'B');
  
  // Check for __NEXT_DATA__
  const nextData = r.body.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
  if (nextData) {
    console.log('  __NEXT_DATA__ found! ' + nextData[1].length + 'B');
    try {
      const nd = JSON.parse(nextData[1]);
      console.log('  Keys: ' + Object.keys(nd.props?.pageProps || nd.props || nd).join(', '));
    } catch {}
  }
  
  // Check for content IDs in any format
  const hsIds = r.body.match(/1[0-9]{9}/g);
  if (hsIds && hsIds.length > 0) {
    const unique = [...new Set(hsIds)];
    console.log('  HS-format IDs: ' + unique.length + ' -> ' + unique.slice(0,5).join(', '));
  }
}

// ═══ 2. Try Hotstar deep links ═══
console.log('\n=== 2. HOTSTAR DEEP LINKS ===\n');

const hsUrls = [
  'https://www.hotstar.com/in/tv/ironheart/1271341039',
  'https://www.hotstar.com/in/shows/ironheart/1271341039',
  'https://www.hotstar.com/in/tv/loki/1260063451',
  'https://www.hotstar.com/in/movies/mulan/1260048586',
  'https://www.hotstar.com/in/tv/lanterns/1271680756',
  // API patterns
  'https://api.hotstar.com/o/v1/page/1271341039',
  'https://api.hotstar.com/o/v2/page/1271341039',
];

for (const url of hsUrls) {
  const r = await req(url);
  const preview = r.body.substring(0, 120).replace(/\n/g, ' ');
  console.log(url.substring(url.indexOf('.com/')+4, url.indexOf('.com/')+60) + ': ' + r.status + ' | ' + r.body.length + 'B');
  
  // Extract any season/episode data
  const seasonRe = /season[_"]?\s*[:=]\s*"?(\d{10})/gi;
  let m;
  while ((m = seasonRe.exec(r.body)) !== null) {
    console.log('  SEASON ID: ' + m[1]);
  }
  
  // Check for __NEXT_DATA__
  const nd = r.body.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
  if (nd) {
    console.log('  __NEXT_DATA__ found! ' + nd[1].length + 'B');
    try {
      const data = JSON.parse(nd[1]);
      const str = JSON.stringify(data);
      // Look for content IDs
      const ids = str.match(/1[0-9]{9}/g);
      if (ids) console.log('  HS IDs in __NEXT_DATA__: ' + [...new Set(ids)].slice(0,10).join(', '));
    } catch {}
  }
}

// ═══ 3. JioCinema API v3 (GraphQL / REST) ═══
console.log('\n=== 3. JIOCINEMA APIs ===\n');

const jcApis = [
  'https://apis.jiocinema.com/content/query/search?q=ironheart&types=SHOW',
  'https://apis.jiocinema.com/v3.1/content/query/search?q=ironheart',
  'https://apis.jiocinema.com/content/v1/search?q=ironheart',
  'https://content-jiovoot.voot.com/psapi/media/voot/v1/search?q=ironheart&rows=5',
  'https://apis.jiocinema.com/content/v2/detail/ironheart',
  // Try with different headers
];

for (const url of jcApis) {
  const r = await req(url, {headers: {'x-platform': 'web', 'x-platform-token': 'web'}});
  console.log(url.substring(url.indexOf('.com/')+4, url.indexOf('.com/')+60) + ': ' + r.status + ' | ' + r.body.substring(0,100).replace(/\n/g,' '));
}

// ═══ 4. Google search for HS IDs ═══
console.log('\n=== 4. WEB SEARCH FOR HS IDs ===\n');

// Try DuckDuckGo (no JS needed)
const ddgR = await req('https://html.duckduckgo.com/html/?q=site%3Ahotstar.com+ironheart');
console.log('DuckDuckGo: ' + ddgR.status + ' | ' + ddgR.body.length + 'B');
// Extract hotstar URLs from results
const hsUrlRe = /hotstar\.com[^"'\s]*\/(\d{10})/g;
const foundIds = new Set();
while ((m = hsUrlRe.exec(ddgR.body)) !== null) foundIds.add(m[1]);
console.log('Found IDs: ' + [...foundIds].join(', '));

// Try for Lanterns
const ddg2 = await req('https://html.duckduckgo.com/html/?q=site%3Ahotstar.com+lanterns+tv+show');
const foundIds2 = new Set();
while ((m = hsUrlRe.exec(ddg2.body)) !== null) foundIds2.add(m[1]);
console.log('Lanterns IDs: ' + [...foundIds2].join(', '));

// ═══ 5. Test if episodes.php works without cookies using SEASON IDs ═══
console.log('\n=== 5. EPISODES.PHP WITHOUT COOKIES (season IDs) ===\n');

const seasonIds = [
  {name: 'Ironheart S1', id: '1271341037'},
  {name: 'Loki S1', id: '1260063524'},
  {name: 'Loki S2', id: '1260148326'},
  {name: 'Lanterns S1', id: '1271684185'},
];

for (const s of seasonIds) {
  const r = await req(BASE + '/mobile/hs/episodes.php?s=' + s.id + '&p=0');
  try {
    const p = JSON.parse(r.body);
    const eps = p?.episodes || [];
    if (eps.length > 0) {
      console.log('✅ ' + s.name + ' (id=' + s.id + '): ' + eps.length + ' eps | E1=' + eps[0].id + ' "' + eps[0].t + '"');
    } else {
      console.log('❌ ' + s.name + ': empty');
    }
  } catch { console.log('? ' + s.name + ': ' + r.body.substring(0,60)); }
}
