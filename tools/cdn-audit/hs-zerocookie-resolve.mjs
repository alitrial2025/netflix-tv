#!/usr/bin/env node
/**
 * HS Zero-Cookie ID Resolution via DuckDuckGo + JioCinema + episodes.php
 */
import https from 'https';

function req(url) {
  return new Promise((resolve) => {
    const u = new URL(url);
    const r = https.request({
      hostname: u.hostname, path: u.pathname + u.search, method: 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' },
      rejectUnauthorized: false, timeout: 15000
    }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://' + u.hostname + res.headers.location;
        req(loc).then(resolve); res.resume(); return;
      }
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    r.on('error', e => resolve({ status: 0, body: e.message }));
    r.end();
  });
}

console.log('╔═══════════════════════════════════════════════════════╗');
console.log('║  HS ZERO-COOKIE ID RESOLUTION — All Approaches       ║');
console.log('╚═══════════════════════════════════════════════════════╝\n');

// ═══ 1. DuckDuckGo search for Hotstar IDs ═══
console.log('=== 1. DUCKDUCKGO: site:hotstar.com ===\n');

const queries = [
  'ironheart', 'lanterns', 'loki', 'mulan', 'mandalorian', 'ahsoka'
];

for (const q of queries) {
  const ddg = await req('https://html.duckduckgo.com/html/?q=site%3Ahotstar.com+' + encodeURIComponent(q));
  const re = /hotstar\.com[^"'\s<>]*\/(\d{10})/g;
  const ids = new Set();
  let m;
  while ((m = re.exec(ddg.body)) !== null) ids.add(m[1]);
  console.log(q + ': ' + ids.size + ' IDs → ' + [...ids].join(', '));
}

// ═══ 2. JioCinema content API v2 (try different patterns) ═══
console.log('\n=== 2. JIOCINEMA CONTENT APIs ===\n');

const jcPatterns = [
  'https://content-jiovoot.voot.com/psapi/jio/cms/content/search?q=ironheart&rows=5&start=0',
  'https://apis.jiocinema.com/v3/content/search?q=ironheart',
  'https://apis.jiocinema.com/content/search?q=ironheart&types=SHOW',
  'https://apis.jiocinema.com/content/search?q=ironheart',
  'https://content-jiovoot.voot.com/psapi/jio/cms/search?q=ironheart',
  'https://apis.jiocinema.com/graphql',
];

for (const url of jcPatterns) {
  const r = await req(url);
  const preview = r.body.substring(0, 100).replace(/\n/g, ' ');
  console.log(url.substring(url.indexOf('.com/') + 4, url.indexOf('.com/') + 55) + ': ' + r.status + ' | ' + preview);
}

// ═══ 3. Try Google cache / web.archive.org for Hotstar show pages ═══
console.log('\n=== 3. WEB ARCHIVE / GOOGLE CACHE ===\n');

const archiveUrls = [
  'https://web.archive.org/web/2024/https://www.hotstar.com/in/tv/loki/1260063451',
  'https://web.archive.org/web/2024/https://www.hotstar.com/in/tv/ironheart/1271341039',
];

for (const url of archiveUrls) {
  const r = await req(url);
  const hasIds = (r.body.match(/1[0-9]{9}/g) || []).filter(id => id.startsWith('126') || id.startsWith('127'));
  console.log(url.substring(url.indexOf('hotstar')) + ': ' + r.status + ' | ' + r.body.length + 'B | HS IDs: ' + [...new Set(hasIds)].slice(0, 5).join(', '));
}

// ═══ 4. Test HS episodes.php WITHOUT cookies (with known season IDs) ═══
console.log('\n=== 4. HS EPISODES.PHP WITHOUT COOKIES ===\n');

const seasonTests = [
  { name: 'Ironheart S1', seasonId: '1271341037', showId: '1271341039' },
  { name: 'Loki S1', seasonId: '1260063524', showId: '1260063451' },
  { name: 'Loki S2', seasonId: '1260148326', showId: '1260063451' },
  { name: 'Lanterns S1', seasonId: '1271684185', showId: '1271680756' },
];

for (const s of seasonTests) {
  // Try both hs/ prefix and main /mobile/ 
  for (const path of [
    '/mobile/hs/episodes.php?s=' + s.seasonId + '&series=' + s.showId + '&p=0',
    '/mobile/episodes.php?s=' + s.seasonId + '&series=' + s.showId + '&p=0',
  ]) {
    const r = await req('https://net52.cc' + path);
    try {
      const p = JSON.parse(r.body);
      const eps = (p?.episodes || []).filter(Boolean);
      if (eps.length > 0) {
        console.log('✅ ' + s.name + ' ' + path.split('?')[0] + ': ' + eps.length + ' eps | E1=' + eps[0].id + ' "' + eps[0].t + '"');
      } else {
        console.log('⚠️ ' + s.name + ' ' + path.split('?')[0] + ': empty (nextPage=' + (p?.nextPageSeason || '') + ')');
      }
    } catch {
      console.log('❌ ' + s.name + ' ' + path.split('?')[0] + ': ' + r.body.substring(0, 50));
    }
  }
}

// ═══ 5. The KEY test: HS search.php with NO cookies ═══
console.log('\n=== 5. HS SEARCH.PHP — ZERO COOKIE VARIATIONS ===\n');

const searchQueries = ['ironheart', 'loki', 'lanterns', 'mulan'];

for (const sq of searchQueries) {
  // Try /mobile/hs/search.php with no cookies
  const r1 = await req('https://net52.cc/mobile/hs/search.php?s=' + encodeURIComponent(sq) + '&t=' + Math.floor(Date.now() / 1000));
  try {
    const j = JSON.parse(r1.body);
    const results = j.searchResult || [];
    if (results.length > 0) {
      console.log('✅ hs/search no-cookie "' + sq + '": ' + results.map(r => r.id + '=' + r.t).join(', '));
    } else {
      console.log('❌ hs/search no-cookie "' + sq + '": ' + (j.error || 'empty'));
    }
  } catch {
    console.log('❌ hs/search no-cookie "' + sq + '": ' + r1.body.substring(0, 50));
  }

  // Try /search.php (non-mobile) with no cookies
  const r2 = await req('https://net52.cc/search.php?s=' + encodeURIComponent(sq) + '&t=' + Math.floor(Date.now() / 1000));
  try {
    const j = JSON.parse(r2.body);
    const results = j.searchResult || [];
    if (results.length > 0) {
      console.log('   /search.php no-cookie "' + sq + '": ' + results.slice(0, 2).map(r => r.id + '=' + r.t).join(', '));
    }
  } catch {}
}

// ═══ 6. Try TMDB → Hotstar ID mapping ═══
console.log('\n=== 6. TMDB EXTERNAL IDs ===\n');

const tmdbUrls = [
  'https://api.themoviedb.org/3/tv/114472/external_ids', // Ironheart
  'https://api.themoviedb.org/3/tv/84958/external_ids',  // Loki
];

for (const url of tmdbUrls) {
  const r = await req(url);
  console.log(url.substring(url.indexOf('.org/') + 4) + ': ' + r.status + ' | ' + r.body.substring(0, 100));
}
