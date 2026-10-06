#!/usr/bin/env node
import https from 'https';
import { readFile, writeFile } from 'fs/promises';

const GATU = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';

function req(url, hdrs={}) {
  return new Promise((resolve, reject) => {
    https.get(url, {headers:{'User-Agent':GATU,'X-Requested-With':'XMLHttpRequest',...hdrs},rejectUnauthorized:false,timeout:15000}, res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>resolve({status:res.statusCode, body:d}));
    }).on('error',e=>resolve({status:0,body:e.message}));
  });
}

const session = JSON.parse(await readFile('C:\\Users\\mzazimhenga\\AppData\\Local\\Temp\\cdn-diagnosis-iPkTf1\\session.json','utf8'));
const cookie = 'addhash='+session.addhashEncoded+'; t_hash_t='+session.tHashTEncoded+'; lang=eng';

const paths = ['/mobile/series', '/mobile/movies', '/mobile/series.php', '/mobile/movies.php'];

for (const path of paths) {
  const r = await req('https://net52.cc'+path, {'Cookie':cookie});
  console.log(path + ': ' + r.status + ' | ' + r.body.length + 'B');
  if (r.body.length > 100) {
    // OTT mentions
    const ottRe = /prime|disney|hotstar|amazon|apple|hbo|paramount|peacock|zee5/gi;
    const otts = r.body.match(ottRe);
    if (otts) console.log('  OTTs:', [...new Set(otts.map(m=>m.toLowerCase()))].join(', '));
    
    // IDs in poster URLs
    const posterRe = /imgcdn[^"']*\/(\d{7,9})\./g;
    const posterIds = new Set();
    let m;
    while ((m = posterRe.exec(r.body)) !== null) posterIds.add(m[1]);
    console.log('  Poster IDs:', posterIds.size);
    if (posterIds.size > 0) console.log('  Sample:', [...posterIds].slice(0,10).join(', '));
    
    // Data attributes  
    const dataRe = /data-[\w]+="[^"]+"/g;
    const dataAttrs = r.body.match(dataRe) || [];
    const uniqueNames = new Set();
    for (const d of dataAttrs) {
      const name = d.match(/data-([\w]+)/)[1];
      uniqueNames.add(name);
    }
    console.log('  Data attrs:', [...uniqueNames].join(', '));
    
    // Headings
    const headRe = /<h[1-6][^>]*>([^<]+)<\/h[1-6]>/gi;
    const heads = [];
    while ((m = headRe.exec(r.body)) !== null) heads.push(m[1].trim());
    if (heads.length) console.log('  Headings:', heads.join(' | '));
    
    // onclick/href with paths
    const linkRe = /(?:onclick|href)=['"](\/mobile\/[^'"]+|[^'"]*\.php[^'"]*)['"]/gi;
    const links = new Set();
    while ((m = linkRe.exec(r.body)) !== null) links.add(m[1]);
    if (links.size) {
      console.log('  Links:');
      for (const l of links) console.log('    ' + l.substring(0,100));
    }
    
    const safeName = path.replace(/\//g, '_').replace(/^_/, '');
    await writeFile('tools/cdn-audit/page' + safeName + '.html', r.body);
  }
}

// Also check if the 121KB home has content IDs embedded differently
console.log('\n--- Home page ID extraction ---');
const home = await readFile('tools/cdn-audit/home-full.html', 'utf8');

// Poster image IDs
const posterRe2 = /(?:imgcdn|poster)[^"']*\/(\d{7,9})/g;
const homeIds = new Set();
let m2;
while ((m2 = posterRe2.exec(home)) !== null) homeIds.add(m2[1]);
console.log('Poster IDs in home:', homeIds.size);
if (homeIds.size > 0) {
  console.log('Sample:', [...homeIds].slice(0,20).join(', '));
}

// data-id, data-nfid, data-show, etc
const dataIdRe = /data-(?:id|nfid|show|movie|content)="([^"]+)"/gi;
const dataIds = new Set();
while ((m2 = dataIdRe.exec(home)) !== null) dataIds.add(m2[1]);
console.log('Data IDs:', dataIds.size);
if (dataIds.size > 0) console.log('Sample:', [...dataIds].slice(0,10).join(', '));

// onclick with IDs
const onclickIdRe = /onclick="[^"]*(\d{7,9})[^"]*"/g;
const clickIds = new Set();
while ((m2 = onclickIdRe.exec(home)) !== null) clickIds.add(m2[1]);
console.log('OnClick IDs:', clickIds.size);
if (clickIds.size > 0) console.log('Sample:', [...clickIds].slice(0,10).join(', '));
