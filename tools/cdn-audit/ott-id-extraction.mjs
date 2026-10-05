#!/usr/bin/env node
/**
 * Find season/episode IDs for other OTTs without cookies
 * Same strategy as Netflix: scrape the original platform's public pages
 * 
 * PV IDs from MITM: 0QBPFM3PCGAM8ZKSC7UZ16PBOE (Lost)
 * PV episode:        0H7HKT2M8ELZS4N6JR0AF1JID2 (Lost S6E2)
 */
import https from 'https';
import { writeFile } from 'fs/promises';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: 'GET',
      headers: {
        'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36',
        'Accept':'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language':'en-US,en;q=0.9',
        ...opts.headers
      },
      rejectUnauthorized:false, timeout:20000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
        console.log('  Redirect -> ' + loc.substring(0,100));
        req(loc, opts).then(resolve).catch(reject);
        res.resume();
        return;
      }
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers}));
    });
    r.on('error',e=>resolve({status:0,body:e.message,headers:{}}));
    r.on('timeout',()=>{r.destroy();reject(new Error('timeout'))});
    r.end();
  });
}

const PV_SHOW = '0QBPFM3PCGAM8ZKSC7UZ16PBOE';  // Lost
const PV_EP = '0H7HKT2M8ELZS4N6JR0AF1JID2';    // Lost S6E2

console.log('╔═══════════════════════════════════════════╗');
console.log('║  OTT ID EXTRACTION — Like Netflix.com    ║');
console.log('╚═══════════════════════════════════════════╝\n');

// ═══════════════════════════════════════
// 1. PRIME VIDEO — Try primevideo.com
// ═══════════════════════════════════════
console.log('═══ 1. PRIME VIDEO (primevideo.com) ═══\n');

// Try known PV URL patterns
const pvUrls = [
  'https://www.primevideo.com/detail/' + PV_SHOW,
  'https://www.primevideo.com/detail/' + PV_SHOW + '/ref=atv_dp_season_select_s1',
  'https://www.amazon.com/dp/' + PV_SHOW,
  'https://www.amazon.com/gp/video/detail/' + PV_SHOW,
  'https://www.primevideo.com/detail/Lost/'+PV_SHOW,
];

for (const url of pvUrls) {
  console.log('Trying: ' + url.substring(0,80));
  const r = await req(url);
  console.log('  Status: ' + r.status + ' | Size: ' + r.body.length + 'B');
  
  if (r.body.length > 5000) {
    // Look for the alphanumeric IDs like net52 uses
    const pvIdPattern = /\b(0[A-Z0-9]{25})\b/g;
    const ids = new Set();
    let m;
    while ((m = pvIdPattern.exec(r.body)) !== null) ids.add(m[1]);
    if (ids.size > 0) {
      console.log('  ✅ Found ' + ids.size + ' PV-style IDs!');
      console.log('  Sample: ' + [...ids].slice(0,10).join(', '));
      // Check if our known show/ep IDs are in there
      console.log('  Contains show ID: ' + ids.has(PV_SHOW));
      console.log('  Contains ep ID: ' + ids.has(PV_EP));
    }
    
    // Also look for standard ASINs (10 chars alphanumeric)
    const asinPattern = /\b(B0[A-Z0-9]{8})\b/g;
    const asins = new Set();
    while ((m = asinPattern.exec(r.body)) !== null) asins.add(m[1]);
    if (asins.size > 0) console.log('  ASINs: ' + [...asins].slice(0,5).join(', '));

    // Look for "titleId" or similar in JSON data
    const titleIdPattern = /titleId['":\s]+["']?([A-Za-z0-9_-]+)["']?/g;
    while ((m = titleIdPattern.exec(r.body)) !== null) {
      console.log('  titleId: ' + m[1]);
    }
    
    await writeFile('tools/cdn-audit/pv-page.html', r.body);
    break;
  }
}

// ═══════════════════════════════════════
// 2. Try PV episodes endpoint directly
// ═══════════════════════════════════════
console.log('\n═══ 2. PV EPISODES ENDPOINT ═══\n');

// Maybe there IS a PV episodes.php we just never saw in MITM
const pvEpEndpoints = [
  '/mobile/pv/episodes.php?s=' + PV_SHOW + '&p=0',
  '/mobile/pv/episodes.php?s=' + PV_EP + '&p=0',
  '/mobile/pv/episodes.php?id=' + PV_SHOW + '&p=0',
  // Try season numbers
  '/mobile/pv/post.php?id=' + PV_SHOW + '&s=1',
  '/mobile/pv/post.php?id=' + PV_SHOW + '&season=1',
];

for (const ep of pvEpEndpoints) {
  const r = await req('https://net52.cc' + ep, {headers:{'X-Requested-With':'XMLHttpRequest'}});
  const preview = r.body.substring(0, 100).replace(/\n/g,' ');
  console.log(ep.substring(0,70) + ': ' + r.status + ' | ' + preview);
}

// ═══════════════════════════════════════
// 3. JIOHOTSTAR — Try hotstar.com
// ═══════════════════════════════════════
console.log('\n═══ 3. JIOHOTSTAR ═══\n');

// Try HS endpoints with known IDs — extract some HS show IDs from the browse page
// First check if HS search works and returns IDs
const hsSearchR = await req('https://net52.cc/mobile/hs/search.php?s=game+of+thrones', {headers:{'X-Requested-With':'XMLHttpRequest'}});
console.log('HS search "game of thrones": ' + hsSearchR.status + ' | ' + hsSearchR.body.substring(0,150));

const hsSearchR2 = await req('https://net52.cc/mobile/hs/search.php?s=lost', {headers:{'X-Requested-With':'XMLHttpRequest'}});
console.log('HS search "lost": ' + hsSearchR2.status + ' | ' + hsSearchR2.body.substring(0,150));

// Check HS post with a Hotstar numeric ID format
const hsIds = ['1260050764', '1260009879', '1000272071']; // common Hotstar show IDs
for (const id of hsIds) {
  const r = await req('https://net52.cc/mobile/hs/post.php?id=' + id + '&t=' + Math.floor(Date.now()/1000), {headers:{'X-Requested-With':'XMLHttpRequest'}});
  console.log('HS post.php id=' + id + ': ' + r.status + ' | ' + r.body.substring(0,100).replace(/\n/g,' '));
}

// ═══════════════════════════════════════
// 4. Try PV search on net52
// ═══════════════════════════════════════
console.log('\n═══ 4. PV SEARCH ON NET52 ═══\n');

// The non-mobile search.php works without cookies but only returns NF results
// Try with explicit params
const pvSearchTerms = ['lost', 'the boys', 'reacher', 'jack ryan'];
for (const term of pvSearchTerms) {
  const r = await req('https://net52.cc/mobile/pv/search.php?s=' + encodeURIComponent(term), {headers:{'X-Requested-With':'XMLHttpRequest'}});
  try {
    const p = JSON.parse(r.body);
    const items = p?.searchResult || [];
    if (items.length > 0) {
      console.log('"' + term + '" -> ' + items.length + ' results: id=' + items[0].id + ' "' + items[0].t + '"');
    } else {
      console.log('"' + term + '" -> ' + (p?.error || p?.head || 'empty'));
    }
  } catch {
    console.log('"' + term + '" -> ' + r.status + ' ' + r.body.substring(0,60));
  }
}

// ═══════════════════════════════════════
// 5. Try JustWatch API for ID mapping
// ═══════════════════════════════════════
console.log('\n═══ 5. JUSTWATCH API ═══\n');

const jwR = await req('https://apis.justwatch.com/contentpartner/v2/content/offers/object_type/show/locale/en_US?title=Lost&providers=prv', {
  headers: {'Accept':'application/json'}
});
console.log('JustWatch: ' + jwR.status + ' | ' + jwR.body.substring(0,150));

// Also try the graphql
const jwR2 = await req('https://apis.justwatch.com/content/titles/en_US/popular?body=%7B%22query%22%3A%22Lost%22%7D');
console.log('JustWatch v2: ' + jwR2.status + ' | ' + jwR2.body.substring(0,150));
