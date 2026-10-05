#!/usr/bin/env node
/**
 * HS final hunt — JioCinema content API + Hotstar deep links + wider scan
 */
import https from 'https';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search,
      method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept':'application/json,text/html,*/*','Accept-Language':'en-US,en;q=0.9', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    });
    r.on('error',e=>resolve({status:0,body:e.message}));
    r.on('timeout',()=>{r.destroy();reject(new Error('timeout'))});
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

const BASE = 'https://net52.cc';

console.log('╔═══════════════════════════════════════════════╗');
console.log('║  HS FINAL HUNT                                ║');
console.log('╚═══════════════════════════════════════════════╝\n');

// ═══ 1. JioCinema GraphQL / content API ═══
console.log('═══ 1. JIOCINEMA CONTENT APIs ═══\n');

const jcEndpoints = [
  // JioCinema v3 API
  'https://content-jiovoot.voot.com/psapi/media/voot/v1/voot-web/content/generic/series-master-content/3698?responseType=common',
  'https://content-jiovoot.voot.com/psapi/media/voot/v1/voot-web/content/generic/season-by-show/3698?responseType=common&pageNo=1&pageSize=20',
  // JioCinema v4 API patterns
  'https://apis.jiocinema.com/v3.1/content/detail/3698',
  'https://apis.jiocinema.com/v3.1/content/seasons/3698',
  // Alternative patterns
  'https://prod.media.jio.com/apis/common/v3/metamore/tvshow/3698',
  // Hotstar API v2
  'https://api.hotstar.com/o/v2/show/detail?contentId=1260009597',
  'https://api.hotstar.com/o/v1/show/detail?contentId=1260009597',
  'https://api.hotstar.com/h/v2/play/in/contents/1260009597',
];

for (const url of jcEndpoints) {
  const r = await req(url);
  const preview = r.body.substring(0,150).replace(/\n/g,' ');
  console.log(url.substring(0,70) + '...');
  console.log('  ' + r.status + ' | ' + preview);
  
  if (r.status === 200 && r.body.length > 200) {
    try {
      const j = JSON.parse(r.body);
      // Look for season info
      const str = JSON.stringify(j);
      const seasonRe = /season[_]?[Ii]d["':]+\s*["']?(\d+)/g;
      let m;
      while ((m = seasonRe.exec(str)) !== null) {
        console.log('  SEASON ID: ' + m[1]);
      }
    } catch {}
  }
}

// ═══ 2. Wide scan of HS episodes.php ═══
console.log('\n═══ 2. WIDER SCAN (1260009xxx range) ═══\n');

// GOT is 1260009597, maybe seasons are 1260009xxx
const ranges = [
  [1260009500, 1260009650], // near GOT
  [1260050700, 1260050820], // near Criminal Justice
  [1260019000, 1260019100], // near Aarya
];

for (const [start, end] of ranges) {
  process.stdout.write('Scanning ' + start + '-' + end + '...');
  let found = 0;
  for (let id = start; id <= end; id++) {
    const r = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
    try {
      const p = JSON.parse(r.body);
      const eps = p?.episodes || [];
      if (eps.length > 0) {
        console.log('\n  ✅ ' + id + ': ' + eps.length + ' eps | ' + eps[0].s + eps[0].ep + ' "' + eps[0].t + '"');
        found++;
      }
    } catch {}
  }
  console.log(found ? '' : ' 0 hits');
}

// ═══ 3. Try completely different ID ranges ═══
console.log('\n═══ 3. RANDOM HS ID RANGES ═══\n');

// Maybe HS season IDs use a different numbering scheme
const randomRanges = [
  [1, 50],           // Very low IDs
  [100, 150],        // Low
  [1000, 1050],      // Medium
  [10000, 10050],    // Higher
  [100000, 100050],  // Even higher
  [500000, 500020],  // Half million
];

for (const [start, end] of randomRanges) {
  let hits = [];
  for (let id = start; id <= end; id++) {
    const r = await req(BASE + '/mobile/hs/episodes.php?s=' + id + '&p=0');
    try {
      const p = JSON.parse(r.body);
      const eps = p?.episodes || [];
      if (eps.length > 0) {
        hits.push({id, count: eps.length, s: eps[0].s, title: eps[0].t});
      }
    } catch {}
  }
  if (hits.length > 0) {
    console.log(start + '-' + end + ': ' + hits.length + ' hits!');
    for (const h of hits) {
      console.log('  ✅ ' + h.id + ': ' + h.count + ' eps | ' + h.s + ' "' + h.title + '"');
    }
  } else {
    console.log(start + '-' + end + ': 0 hits');
  }
}
