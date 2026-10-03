#!/usr/bin/env node
// User-initiated public sitemap metadata export. Partner IDs are candidates, never playback authorization.
import {mkdir,writeFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const norm=s=>String(s).normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]/g,'');
const source='https://www.airtelxstream.in/sitemap/parent_sitemap.xml';
async function metadata(url) {
 const u=new URL(url);if(u.protocol!=='https:'||u.hostname!=='www.airtelxstream.in')throw Error('Unexpected catalog URL');
 const r=await fetch(url,{headers:{'User-Agent':'ChatGPT-User'},credentials:'omit',redirect:'error',signal:AbortSignal.timeout(15000)});
 if(!r.ok)throw Error(`Public catalog unavailable: HTTP ${r.status}`);
 let bytes=Buffer.from(await r.arrayBuffer());if(bytes.length>8*1024*1024)throw Error('Catalog response budget exceeded');
 if(bytes[0]===0x1f&&bytes[1]===0x8b)bytes=gunzipSync(bytes,{maxOutputLength:32*1024*1024});
 return bytes.toString('utf8');
}
const parent=await metadata(source);
const maps=[...parent.matchAll(/<loc>([^<]+)<\/loc>/g)].map(m=>m[1]).filter(u=>/\/(tvshows_\d+|movie_\d+)\.xml\.gz$/.test(u));
if(maps.length<2||maps.length>30)throw Error('Unexpected catalog sitemap count');
const unique=new Map();
for(const map of maps) {
 const xml=await metadata(map);
 for(const m of xml.matchAll(/<loc>([^<]+)<\/loc>/g)) {
  const u=new URL(m[1]);if(u.hostname!=='www.airtelxstream.in')continue;
  const p=u.pathname.match(/^\/(tv-shows|movies)\/([a-z0-9-]+)\/HOTSTAR_DTH_(TVSHOW|MOVIE)_([0-9]{5,20})$/);
  if(!p||(p[1]==='tv-shows')!==(p[3]==='TVSHOW'))continue;
  const row=[p[1]==='tv-shows'?'tv':'movie',norm(p[2]),p[4],u.pathname];
  unique.set(JSON.stringify(row),row);
 }
}
const rows=[...unique.values()];if(rows.length<1000)throw Error('Incomplete catalog; keep the previous snapshot');
const report={schemaVersion:1,generatedAt:new Date().toISOString(),source,
 scope:'Published partner catalog candidates. Short partner movie IDs are not native Hotstar IDs; extract official links from the public title page and validate the provider response.',rows};
const args=process.argv.slice(2);const pos=args.indexOf('--output');
const output=pos>=0?args[pos+1]:fileURLToPath(new URL('../../app/src/main/assets/public-hotstar-catalog.json',import.meta.url));
await mkdir(dirname(output),{recursive:true});await writeFile(output,JSON.stringify(report));
console.log(JSON.stringify({rows:rows.length,movies:rows.filter(r=>r[0]==='movie').length,series:rows.filter(r=>r[0]==='tv').length}));
