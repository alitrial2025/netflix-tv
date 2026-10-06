#!/usr/bin/env node
/**
 * HS ZERO-COOKIE: Final approaches to solve search + season discovery
 * 
 * Key proven chain: episodes.php → playlist.php → HLS → CDN all work WITHOUT cookies
 * Missing: show ID + season IDs from a title query without cookies
 * 
 * Approaches:
 * 1. TMDB website scraping (no API key needed)
 * 2. Disney+ website deep links  
 * 3. Hotstar SEO-friendly URL patterns
 * 4. JioCinema mobile/API endpoints
 * 5. Google custom search
 * 6. HS post.php WITHOUT cookies (never tested without cookies before!)
 */
import https from 'https';

function req(url, opts={}) {
  return new Promise((resolve) => {
    const u = new URL(url);
    https.request({
      hostname: u.hostname, path: u.pathname + u.search, method: opts.method || 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/json,*/*',
        ...opts.headers
      },
      rejectUnauthorized: false, timeout: 15000
    }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http')
          ? res.headers.location
          : 'https://' + u.hostname + res.headers.location;
        req(loc, opts).then(resolve); res.resume(); return;
      }
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    }).on('error', e => resolve({ status: 0, body: e.message })).end(opts.body || undefined);
  });
}

console.log('╔═══════════════════════════════════════════════════════╗');
console.log('║  HS ZERO-COOKIE: Final Resolution Attempts           ║');
console.log('╚═══════════════════════════════════════════════════════╝\n');

// ═══ 1. TMDB website scraping (no API key) ═══
console.log('=== 1. TMDB WEBSITE SCRAPING ===\n');

const tmdbPages = [
  { name: 'Ironheart', url: 'https://www.themoviedb.org/tv/114472-ironheart' },
  { name: 'Loki', url: 'https://www.themoviedb.org/tv/84958-loki' },
  { name: 'Lanterns', url: 'https://www.themoviedb.org/tv/209867-lanterns' },
];

for (const p of tmdbPages) {
  const r = await req(p.url);
  // Look for Hotstar/JioCinema links
  const hsLink = r.body.match(/hotstar\.com[^"'<>\s]*/g);
  const jcLink = r.body.match(/jiocinema\.com[^"'<>\s]*/g);
  const dpLink = r.body.match(/disneyplus\.com[^"'<>\s]*/g);
  console.log(p.name + ' TMDB: ' + r.status + ' | ' + r.body.length + 'B');
  if (hsLink) console.log('  Hotstar links: ' + [...new Set(hsLink)].join(', '));
  if (jcLink) console.log('  JioCinema links: ' + [...new Set(jcLink)].join(', '));
  if (dpLink) console.log('  Disney+ links: ' + [...new Set(dpLink)].join(', '));
  if (!hsLink && !jcLink && !dpLink) console.log('  No streaming links found in HTML');
  
  // Also try the "watch providers" page
  const wpR = await req(p.url + '/watch');
  const hsLink2 = wpR.body.match(/hotstar\.com[^"'<>\s]*/g);
  const jcLink2 = wpR.body.match(/jiocinema\.com[^"'<>\s]*/g);
  if (hsLink2) console.log('  /watch Hotstar: ' + [...new Set(hsLink2)].join(', '));
  if (jcLink2) console.log('  /watch JioCinema: ' + [...new Set(jcLink2)].join(', '));
}

// ═══ 2. Disney+ deep links ═══
console.log('\n=== 2. DISNEY+ DEEP LINKS ===\n');

const dpUrls = [
  'https://www.disneyplus.com/series/ironheart/3VDi7mJ7WB4L',
  'https://www.disneyplus.com/series/loki/6pARMvILBGzF',
  'https://www.disneyplus.com/search?q=ironheart',
];

for (const url of dpUrls) {
  const r = await req(url);
  console.log(url.substring(url.indexOf('.com/') + 4, url.indexOf('.com/') + 50) + ': ' + r.status + ' | ' + r.body.length + 'B');
  const nextData = r.body.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
  if (nextData) {
    console.log('  __NEXT_DATA__ found! ' + nextData[1].length + 'B');
    try {
      const nd = JSON.parse(nextData[1]);
      console.log('  Keys: ' + Object.keys(nd.props?.pageProps || nd).join(', '));
    } catch {}
  }
}

// ═══ 3. THE KEY TEST: HS post.php WITHOUT cookies ═══
console.log('\n=== 3. HS POST.PHP WITHOUT COOKIES ===\n');

const showIds = [
  { name: 'Ironheart', id: '1271341039' },
  { name: 'Loki', id: '1260063451' },
  { name: 'Lanterns', id: '1271680756' },
  { name: 'Mulan', id: '1260048586' },
];

for (const show of showIds) {
  const ts = Math.floor(Date.now() / 1000);
  
  // Try /mobile/hs/post.php without cookies
  const r1 = await req('https://net52.cc/mobile/hs/post.php?id=' + show.id + '&t=' + ts);
  try {
    const j = JSON.parse(r1.body);
    const seasons = j.season || [];
    const title = j.title || '(null)';
    if (seasons.length > 0) {
      console.log('✅ ' + show.name + ' hs/post.php NO-COOKIE: "' + title + '" | ' + seasons.length + ' seasons');
      for (const s of seasons) {
        console.log('   Season ' + s.s + ': id=' + s.id + ' (' + s.ep + ' eps)');
      }
    } else if (j.status === 'y') {
      console.log('⚠️ ' + show.name + ' hs/post.php NO-COOKIE: status=y title="' + title + '" seasons=' + seasons.length + ' type=' + j.type);
    } else {
      console.log('❌ ' + show.name + ' hs/post.php NO-COOKIE: ' + (j.error || j.status || r1.body.substring(0, 60)));
    }
  } catch {
    console.log('❌ ' + show.name + ' hs/post.php NO-COOKIE: ' + r1.body.substring(0, 80));
  }
  
  // Try /mobile/post.php without cookies (main endpoint)
  const r2 = await req('https://net52.cc/mobile/post.php?id=' + show.id + '&t=' + ts);
  try {
    const j = JSON.parse(r2.body);
    if (j.status === 'n') {
      console.log('   /mobile/post.php NO-COOKIE: ' + j.error);
    } else {
      const seasons = j.season || [];
      console.log('   /mobile/post.php NO-COOKIE: "' + (j.title||'null') + '" | seasons=' + seasons.length);
    }
  } catch {}
}

// ═══ 4. Try HS episodes.php with SHOW ID (not season ID) ═══
console.log('\n=== 4. EPISODES.PHP WITH SHOW ID (not season ID) ===\n');

for (const show of showIds) {
  const r = await req('https://net52.cc/mobile/hs/episodes.php?s=' + show.id + '&p=0');
  try {
    const j = JSON.parse(r.body);
    const eps = (j?.episodes || []).filter(Boolean);
    const nextSeason = j?.nextPageSeason || '';
    console.log(show.name + ' (showId=' + show.id + '): ' + eps.length + ' eps | nextPageSeason=' + nextSeason);
    if (eps.length > 0) {
      console.log('  E1=' + eps[0].id + ' "' + eps[0].t + '" ' + eps[0].s + eps[0].ep);
    }
  } catch {
    console.log(show.name + ': ' + r.body.substring(0, 60));
  }
}

// ═══ 5. Google search via Google custom search (no key needed for scraping) ═══  
console.log('\n=== 5. BING SEARCH FOR HOTSTAR IDs ===\n');

const bingQueries = ['ironheart hotstar.com', 'lanterns hotstar.com', 'loki hotstar.com'];
for (const q of bingQueries) {
  const r = await req('https://www.bing.com/search?q=' + encodeURIComponent(q));
  const hsIdRe = /hotstar\.com[^"'\s<>]*\/(\d{10})/g;
  const ids = new Set();
  let m;
  while ((m = hsIdRe.exec(r.body)) !== null) ids.add(m[1]);
  console.log(q + ': ' + ids.size + ' IDs → ' + [...ids].join(', '));
}
