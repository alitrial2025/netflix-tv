#!/usr/bin/env node
/**
 * Search for actual OTT-exclusive content on net52
 * Using titles that are EXCLUSIVELY on Prime Video, Disney+, HBO, etc
 */
import https from 'https';
import { URL } from 'url';

function req(url, opts={}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname+u.search, method: opts.method||'GET',
      headers: {'User-Agent':'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36', 'X-Requested-With':'XMLHttpRequest', ...opts.headers},
      rejectUnauthorized:false, timeout:15000 }, res => {
      let d=''; res.on('data',c=>d+=c); 
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    r.end();
  });
}

const BASE = 'https://net52.cc';

console.log('=== MULTI-OTT EXCLUSIVE SEARCH (no cookies) ===\n');

// OTT-exclusive shows that can ONLY be on that platform
const exclusives = [
  // Prime Video exclusives
  {ott: 'Prime', term: 'invincible'},
  {ott: 'Prime', term: 'rings of power'},
  {ott: 'Prime', term: 'the terminal list'},
  {ott: 'Prime', term: 'citadel'},
  {ott: 'Prime', term: 'fallout'},
  {ott: 'Prime', term: 'upload'},
  {ott: 'Prime', term: 'fleabag'},
  
  // Disney+ exclusives
  {ott: 'Disney+', term: 'andor'},
  {ott: 'Disney+', term: 'ahsoka'},
  {ott: 'Disney+', term: 'echo'},
  {ott: 'Disney+', term: 'secret invasion'},
  {ott: 'Disney+', term: 'obi wan kenobi'},
  
  // HBO/Max exclusives  
  {ott: 'HBO', term: 'the last of us'},
  {ott: 'HBO', term: 'euphoria'},
  {ott: 'HBO', term: 'succession'},
  {ott: 'HBO', term: 'white lotus'},
  {ott: 'HBO', term: 'true detective'},
  
  // Apple TV+ exclusives
  {ott: 'Apple', term: 'severance'},
  {ott: 'Apple', term: 'ted lasso'},
  {ott: 'Apple', term: 'foundation'},
  {ott: 'Apple', term: 'silo'},
  
  // Hulu exclusives
  {ott: 'Hulu', term: 'the bear'},
  {ott: 'Hulu', term: 'shogun'},
  
  // Paramount+ exclusives
  {ott: 'P+', term: 'halo'},
  {ott: 'P+', term: 'tulsa king'},
  {ott: 'P+', term: '1923'},
];

console.log('Testing /search.php (non-mobile, no cookies):');
console.log('');

const found = [];
for (const {ott, term} of exclusives) {
  try {
    const r = await req(BASE + '/search.php?s=' + encodeURIComponent(term));
    const parsed = JSON.parse(r.body);
    const items = parsed?.searchResult || [];
    if (items.length > 0) {
      const exact = items.find(i => (i.t||'').toLowerCase().includes(term.split(' ')[0].toLowerCase()));
      const item = exact || items[0];
      const match = item.t.toLowerCase().includes(term.split(' ')[0].toLowerCase()) ? 'MATCH' : 'partial';
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> id=' + item.id + ' "' + item.t + '" (' + match + ')');
      if (match === 'MATCH') found.push({ott, term, id: item.id, title: item.t});
    } else {
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> NOT FOUND');
    }
  } catch(e) { console.log('[' + ott.padEnd(7) + '] "' + term + '" -> ERROR'); }
}

// Also try mobile search (different endpoint)
console.log('\n\nTesting /mobile/search.php (with different approach):');
for (const {ott, term} of exclusives.slice(0, 10)) {
  try {
    const ts = Math.floor(Date.now()/1000);
    const r = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(term) + '&t=' + ts);
    const parsed = JSON.parse(r.body);
    const status = parsed?.status;
    const head = parsed?.head;
    const items = parsed?.searchResult || [];
    if (status === 'n') {
      // "Top Searches" — needs cookies
      continue;
    }
    if (items.length > 0) {
      const item = items[0];
      console.log('[' + ott.padEnd(7) + '] mobile: "' + term + '" -> id=' + item.id + ' "' + item.t + '"');
    }
  } catch {}
}

console.log('\n\n--- Found OTT-exclusive shows: ---');
for (const s of found) {
  console.log('[' + s.ott + '] "' + s.title + '" id=' + s.id);
}

// For found shows, test if their IDs are Netflix IDs by checking netflix.com
console.log('\n\n--- Verifying ID type (Netflix vs other) ---');
for (const s of found.slice(0, 8)) {
  try {
    // Check if this ID exists on Netflix
    const r = await req('https://www.netflix.com/title/' + s.id, {
      headers: {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0'}
    });
    const isNf = r.body.includes('netflix') && r.body.length > 10000;
    const titleMatch = r.body.match(/"name"\s*:\s*"([^"]+)"/);
    const nfTitle = titleMatch ? titleMatch[1] : '?';
    console.log('[' + s.ott + '] id=' + s.id + ' on netflix.com: ' + (isNf ? 'YES "' + nfTitle + '"' : 'NO (' + r.status + ', ' + r.body.length + 'B)'));
  } catch(e) { console.log('[' + s.ott + '] id=' + s.id + ': error ' + e.message); }
}

// Check playlist.php for found shows
console.log('\n\n--- playlist.php for found shows (no cookies) ---');
for (const s of found.slice(0, 8)) {
  try {
    const r = await req(BASE + '/playlist.php?id=' + s.id);
    const isJson = r.body.startsWith('[') || r.body.startsWith('{');
    if (isJson) {
      const parsed = JSON.parse(r.body);
      const sources = parsed?.[0]?.sources || [];
      console.log('[' + s.ott + '] "' + s.title.substring(0,25) + '": ' + sources.length + ' sources | ' + (sources[0]?.file || '').substring(0, 60));
    }
  } catch {}
}

console.log('\n=== DONE ===');
