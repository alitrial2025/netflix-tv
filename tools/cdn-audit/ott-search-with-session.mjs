#!/usr/bin/env node
/**
 * Use existing session cookies to search for multi-OTT content
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
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(); reject(new Error('timeout')); });
    if(opts.body) r.write(opts.body);
    r.end();
  });
}

// Load session from the diagnostic's private data
const session = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json','utf8'));
const cookieStr = 'addhash=' + session.addhashEncoded + '; t_hash_t=' + session.tHashTEncoded + '; lang=eng';
const BASE = session.baseUrl;
const XHR = {'X-Requested-With':'XMLHttpRequest'};
const FORM = {'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'};

console.log('=== MULTI-OTT SEARCH (with session cookies) ===\n');
const ts = Math.floor(Date.now()/1000);

const ottSearches = [
  // Prime Video exclusives
  {ott: 'Prime', term: 'the boys'},
  {ott: 'Prime', term: 'jack ryan'},
  {ott: 'Prime', term: 'reacher'},
  {ott: 'Prime', term: 'invincible'},
  {ott: 'Prime', term: 'fallout'},
  {ott: 'Prime', term: 'upload'},
  {ott: 'Prime', term: 'fleabag'},
  // Disney+
  {ott: 'Disney+', term: 'loki'},
  {ott: 'Disney+', term: 'mandalorian'},
  {ott: 'Disney+', term: 'andor'},
  {ott: 'Disney+', term: 'moon knight'},
  // HBO/Max
  {ott: 'HBO', term: 'the last of us'},
  {ott: 'HBO', term: 'euphoria'},
  {ott: 'HBO', term: 'succession'},
  {ott: 'HBO', term: 'house of the dragon'},
  {ott: 'HBO', term: 'true detective'},
  {ott: 'HBO', term: 'game of thrones'},
  // Apple TV+
  {ott: 'Apple', term: 'severance'},
  {ott: 'Apple', term: 'ted lasso'},
  {ott: 'Apple', term: 'silo'},
  // Hulu/Paramount+
  {ott: 'Hulu', term: 'the bear'},
  {ott: 'Hulu', term: 'shogun'},
  {ott: 'P+', term: 'yellowstone'},
  {ott: 'P+', term: 'halo'},
  // Netflix (control group)
  {ott: 'Netflix', term: 'stranger things'},
  {ott: 'Netflix', term: 'squid game'},
];

const found = [];

for (const {ott, term} of ottSearches) {
  try {
    const r = await req(BASE + '/mobile/search.php?s=' + encodeURIComponent(term) + '&t=' + ts, {
      headers: {...XHR, 'Cookie': cookieStr}
    });
    const parsed = JSON.parse(r.body);
    const status = parsed?.status;
    const head = parsed?.head;
    const items = parsed?.searchResult || [];
    
    if (status === 'n' || head === 'Top Searches') {
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> TOP SEARCHES (session may have expired)');
      continue;
    }
    
    if (items.length > 0) {
      const exact = items.find(i => {
        const t = (i.t||'').toLowerCase();
        return term.split(' ').every(w => t.includes(w.toLowerCase()));
      });
      const item = exact || items[0];
      const isExact = exact ? 'EXACT' : 'partial(' + items.length + ')';
      const isNumeric = /^\d+$/.test(item.id);
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> id=' + item.id + ' "' + item.t + '" ' + isExact + (isNumeric ? '' : ' [NON-NUMERIC]'));
      if (exact) found.push({ott, term, id: item.id, title: item.t});
    } else {
      console.log('[' + ott.padEnd(7) + '] "' + term + '" -> ' + items.length + ' results (head=' + head + ')');
    }
  } catch(e) { console.log('[' + ott.padEnd(7) + '] "' + term + '" -> ERROR: ' + e.message); }
}

// For found shows, get season data via post.php
console.log('\n\n--- Season data for found shows (via post.php with cookies) ---');
for (const show of found) {
  try {
    const r = await req(BASE + '/mobile/post.php', {
      method:'POST', headers:{...FORM, 'Cookie': cookieStr}, body:'id=' + show.id
    });
    const parsed = JSON.parse(r.body);
    const seasons = parsed?.seasons || [];
    const isMovie = seasons.length === 0 && !parsed?.error;
    
    if (seasons.length > 0) {
      console.log('\n[' + show.ott + '] "' + show.title + '" -> ' + seasons.length + ' seasons');
      for (const s of seasons) {
        const sid = s.id || s.Id;
        const snum = s.s || s.season || s.S || '?';
        const isNumeric = /^\d+$/.test(sid);
        console.log('  S' + snum + ': ID=' + sid + ' ' + (isNumeric ? '(numeric)' : '[NON-NUMERIC: ' + sid + ']'));
      }
    } else if (parsed?.error) {
      console.log('\n[' + show.ott + '] "' + show.title + '" -> ERROR: ' + parsed.error);
    } else {
      console.log('\n[' + show.ott + '] "' + show.title + '" -> MOVIE (no seasons) | body: ' + r.body.substring(0, 100));
    }
  } catch(e) { console.log('\n[' + show.ott + '] post.php error:', e.message); }
}

// Summary
console.log('\n\n========================================');
console.log('MULTI-OTT SUMMARY');
console.log('========================================');
for (const show of found) {
  console.log('[' + show.ott + '] "' + show.title + '" id=' + show.id + ' format=' + (/^\d+$/.test(show.id) ? 'NUMERIC' : 'ALPHA'));
}
