#!/usr/bin/env node
/**
 * Multi-OTT Season Discovery — Test Netflix, Prime Video, Disney+
 * Each OTT uses their real IDs on net52. We fetch season data from the
 * OTT's own public pages and validate against net52.
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36', ...opts.headers},
      rejectUnauthorized:false, timeout:20000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const loc = res.headers.location.startsWith('http') ? res.headers.location : 'https://'+u.hostname+res.headers.location;
        req(loc, opts).then(resolve).catch(reject);
        res.resume();
        return;
      }
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d, headers:res.headers}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    r.end();
  });
}

const BASE = 'https://net52.cc';

console.log('=== MULTI-OTT DISCOVERY (no cookies) ===\n');

// Step 1: What OTTs does net52 support? Check search
console.log('--- Step 1: Discover supported OTTs via search ---');
const testSearches = [
  {term: 'the boys', expect: 'Prime Video'},
  {term: 'jack ryan', expect: 'Prime Video'},
  {term: 'reacher', expect: 'Prime Video'},
  {term: 'loki', expect: 'Disney+'},
  {term: 'mandalorian', expect: 'Disney+'},
  {term: 'wednesday', expect: 'Netflix'},
  {term: 'mr robot', expect: 'Netflix'},
  {term: 'house of dragon', expect: 'HBO'},
  {term: 'yellowjackets', expect: 'Paramount+'},
  {term: 'squid game', expect: 'Netflix'},
];

const foundShows = [];
for (const {term, expect} of testSearches) {
  try {
    const r = await req(BASE + '/search.php?s=' + encodeURIComponent(term), {
      headers: {'X-Requested-With':'XMLHttpRequest'}
    });
    const parsed = JSON.parse(r.body);
    const items = parsed?.searchResult || [];
    if (items.length > 0) {
      const item = items[0];
      console.log('[' + expect + '] "' + term + '" -> id=' + item.id + ' t="' + item.t + '"');
      foundShows.push({term, id: item.id, title: item.t, ott: expect});
    } else {
      console.log('[' + expect + '] "' + term + '" -> NOT FOUND');
    }
  } catch(e) { console.log('[' + expect + '] "' + term + '" -> ERROR: ' + e.message); }
}

// Step 2: For Prime Video shows, check what kind of IDs they use
console.log('\n--- Step 2: ID format analysis ---');
for (const show of foundShows) {
  const id = show.id;
  const isNumeric = /^\d+$/.test(id);
  const isAlphanumeric = /^[A-Z0-9]+$/.test(id);
  const isMixed = /^[a-zA-Z0-9]+$/.test(id);
  console.log(show.ott + ' "' + show.title + '": id=' + id + ' | numeric=' + isNumeric + ' | alpha=' + isAlphanumeric);
}

// Step 3: Test episodes.php and playlist.php for each found show
console.log('\n--- Step 3: episodes.php + playlist.php for each OTT (no cookies) ---');
for (const show of foundShows) {
  console.log('\n[' + show.ott + '] "' + show.title + '" (id=' + show.id + ')');
  
  // Try episodes.php with the show ID as season
  try {
    const r = await req(BASE + '/mobile/episodes.php?s=' + show.id + '&p=0', {
      headers: {'X-Requested-With':'XMLHttpRequest'}
    });
    const parsed = JSON.parse(r.body);
    const eps = parsed?.episodes || [];
    if (eps.length > 0) {
      console.log('  episodes.php: ' + eps.length + ' eps | first: ' + eps[0].s + eps[0].ep + ' "' + (eps[0].t||'').substring(0,30) + '"');
    } else {
      console.log('  episodes.php: empty (show ID is not a season ID)');
    }
  } catch(e) { console.log('  episodes error:', e.message); }
  
  // Try playlist.php
  try {
    const r = await req(BASE + '/playlist.php?id=' + show.id, {
      headers: {'X-Requested-With':'XMLHttpRequest'}
    });
    const isJson = r.body.startsWith('[') || r.body.startsWith('{');
    if (isJson) {
      const parsed = JSON.parse(r.body);
      const sources = parsed?.[0]?.sources || [];
      console.log('  playlist.php: ' + sources.length + ' sources | image: ' + (parsed?.[0]?.image2 || 'none').substring(0,60));
      for (const s of sources.slice(0,2)) {
        console.log('    ' + s.label + ': ' + (s.file||'').substring(0,80));
      }
    } else {
      console.log('  playlist.php: non-JSON (' + r.body.length + 'B)');
    }
  } catch(e) { console.log('  playlist error:', e.message); }
}

// Step 4: For Prime Video shows, try to get season IDs from Amazon
console.log('\n\n--- Step 4: Prime Video season extraction ---');
const primeShows = foundShows.filter(s => s.ott === 'Prime Video');
for (const show of primeShows) {
  console.log('\n[Prime] "' + show.title + '" id=' + show.id);
  
  // Amazon title page
  try {
    const r = await req('https://www.amazon.com/dp/' + show.id, {
      headers: {'Accept':'text/html'}
    });
    console.log('  amazon.com/dp/: HTTP ' + r.status + ' | ' + r.body.length + 'B');
    
    // Extract ASINs (Amazon IDs are 10-char alphanumeric)
    const asinPattern = /\b([A-Z0-9]{10})\b/g;
    const asins = new Set();
    let m;
    while ((m = asinPattern.exec(r.body)) !== null) asins.add(m[1]);
    console.log('  ASINs found: ' + asins.size);
    
    // Try primevideo.com
    const r2 = await req('https://www.primevideo.com/detail/' + show.id, {
      headers: {'Accept':'text/html'}
    });
    console.log('  primevideo.com/detail/: HTTP ' + r2.status + ' | ' + r2.body.length + 'B');
    
    const asins2 = new Set();
    while ((m = asinPattern.exec(r2.body)) !== null) asins2.add(m[1]);
    console.log('  ASINs in primevideo: ' + asins2.size);
    
  } catch(e) { console.log('  Amazon error:', e.message); }
}

// Step 5: For Disney+ shows, try to get from Disney+
console.log('\n\n--- Step 5: Disney+ season extraction ---');
const disneyShows = foundShows.filter(s => s.ott === 'Disney+');
for (const show of disneyShows) {
  console.log('\n[Disney+] "' + show.title + '" id=' + show.id);
  
  // Try disneyplus.com
  try {
    const r = await req('https://www.disneyplus.com/series/' + show.id, {
      headers: {'Accept':'text/html'}
    });
    console.log('  disneyplus.com: HTTP ' + r.status + ' | ' + r.body.length + 'B');
  } catch(e) { console.log('  Disney+ error:', e.message); }
}

// Step 6: Netflix approach for Netflix shows (proven to work)
console.log('\n\n--- Step 6: Netflix season extraction (proven approach) ---');
const nfShows = foundShows.filter(s => s.ott === 'Netflix' && /^\d+$/.test(s.id));
for (const show of nfShows.slice(0, 2)) {
  console.log('\n[Netflix] "' + show.title + '" id=' + show.id);
  try {
    const r = await req('https://www.netflix.com/title/' + show.id, {
      headers: {'Accept':'text/html','Accept-Language':'en-US'}
    });
    
    const idPattern = /\b([5-9]\d{7})\b/g;
    const ids = new Set();
    let m2;
    while ((m2 = idPattern.exec(r.body)) !== null) ids.add(m2[1]);
    console.log('  Netflix IDs found: ' + ids.size);
    
    // Quick validate top 30 against episodes.php
    const candidates = [...ids].slice(0, 30);
    const seasons = [];
    const batch = await Promise.all(candidates.map(async id => {
      try {
        const er = await req(BASE + '/mobile/episodes.php?s=' + id + '&p=0', {
          headers: {'X-Requested-With':'XMLHttpRequest'}
        });
        const p = JSON.parse(er.body);
        const eps = p?.episodes || [];
        if (eps.length > 0) {
          const sNum = parseInt((eps[0]?.s||'').replace(/\D/g,'')) || 0;
          return { id, sNum, epCount: eps.length, first: eps[0]?.t || '' };
        }
      } catch {}
      return null;
    }));
    
    for (const r of batch.filter(Boolean)) {
      console.log('    S' + r.sNum + ': ID=' + r.id + ' | ' + r.epCount + ' eps | "' + r.first.substring(0,25) + '"');
    }
  } catch(e) { console.log('  Error:', e.message); }
}

console.log('\n=== DONE ===');
