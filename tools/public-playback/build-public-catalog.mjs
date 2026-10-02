#!/usr/bin/env node
// Bulk metadata join. These are candidate identities, not a declaration of playable availability.
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const query = `SELECT ?item ?tmdb ?type ?ott ?nativeId WHERE {
 VALUES (?p ?type) { (wdt:P4947 "movie") (wdt:P4983 "tv") }
 VALUES (?n ?ott) { (wdt:P1874 "nf") (wdt:P14440 "pv") (wdt:P11049 "hs") }
 ?item ?p ?tmdb . ?item ?n ?nativeId .
}`;
const u = new URL('https://query.wikidata.org/sparql');
u.search = new URLSearchParams({query,format:'json'});
const r = await fetch(u, {headers:{Accept:'application/sparql-results+json','User-Agent':'NetflixProPublicCatalog/1.0'},
  signal:AbortSignal.timeout(45000),credentials:'omit',redirect:'error'});
if (!r.ok) throw new Error(`Public metadata export unavailable: HTTP ${r.status}`);
const data = await r.json();
const rows = [];
const seen = new Set();
for (const x of data.results.bindings) {
 const type=x.type.value, tmdb=x.tmdb.value, ott=x.ott.value, id=x.nativeId.value, entity=x.item.value.split('/').at(-1);
 if (!/^[1-9]\d*$/.test(tmdb) || !/^Q[1-9]\d*$/.test(entity) ||
     !(ott==='pv' ? /^[A-Z0-9]{10,30}$/.test(id) : /^\d{5,20}$/.test(id))) continue;
 const row=[type,tmdb,ott,id,entity]; const key=JSON.stringify(row);
 if (!seen.has(key)) {seen.add(key);rows.push(row)}
}
rows.sort((a,b)=>a[0].localeCompare(b[0]) || Number(a[1])-Number(b[1]) || a[2].localeCompare(b[2]) || a[3].localeCompare(b[3]));
const report={schemaVersion:1,generatedAt:new Date().toISOString(),source:'https://query.wikidata.org/',
 license:'CC0',scope:'Native identity candidates; validate title/type/year and the live provider response before use.',rows};
const args = process.argv.slice(2); const pos = args.indexOf('--output');
const output = pos >= 0 ? args[pos+1] : fileURLToPath(new URL('../../app/src/main/assets/public-provider-catalog.json',import.meta.url));
await mkdir(dirname(output),{recursive:true});
await writeFile(output,JSON.stringify(report));
await writeFile(new URL('./public-provider-catalog-source.rq',import.meta.url),query);
console.log(JSON.stringify({rows:rows.length,uniqueTitles:new Set(rows.map(x=>`${x[0]}:${x[1]}`)).size,
  counts:rows.reduce((a,x)=>(a[x[2]]=(a[x[2]]||0)+1,a),{}),bytes:Buffer.byteLength(JSON.stringify(report))}));
