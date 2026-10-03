import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {dirname} from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {partnerRow} from './partner-catalog.mjs';
export function makeFeed(native,partner,now=Date.now()) {
 if(native.schemaVersion!==1 || partner.schemaVersion!==1 || !Array.isArray(native.rows) || !Array.isArray(partner.rows) || native.rows.length<1000 || partner.rows.length<1000) throw Error('Incomplete identity sources; retain last published feed');
 if(native.rows.length>200000 || partner.rows.length>100000) throw Error('Catalog row budget exceeded');
 for(const row of native.rows) {
  if(!Array.isArray(row) || !['movie','tv'].includes(row[0]) || !/^[1-9][0-9]*$/.test(row[1]) ||
   !(['nf','hs'].includes(row[2]) ? /^[0-9]{5,20}$/.test(row[3]) : row[2]==='pv' && /^[A-Z0-9]{10,30}$/.test(row[3]))) throw Error('Invalid native identity');
 }
 for(const row of partner.rows) {
  const parsed=Array.isArray(row) && partnerRow(row[3]);
  if(!parsed || JSON.stringify(parsed)!==JSON.stringify(row)) throw Error('Invalid partner identity');
 }
 const feed={schemaVersion:2,generatedAt:now,sourceGeneratedAt:{native:native.generatedAt,partner:partner.generatedAt},
  scope:'Untrusted identity candidates. Verify title, type and requested season/episode against the live provider. No playback URLs or credentials.',
  nativeRows:native.rows,partnerRows:partner.rows};
 if(Buffer.byteLength(JSON.stringify(feed))>8*1024*1024) throw Error('Feed response budget exceeded');
 return feed;
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
 const args=process.argv.slice(2),option=k=>args.includes(k)?args[args.indexOf(k)+1]:undefined;
 const native=JSON.parse(await readFile(option('--native') || new URL('../../app/src/main/assets/public-provider-catalog.json',import.meta.url),'utf8'));
 const partner=JSON.parse(await readFile(option('--partner') || new URL('../../app/src/main/assets/public-hotstar-catalog.json',import.meta.url),'utf8'));
 const feed=makeFeed(native,partner);
 const output=option('--output') || fileURLToPath(new URL('../../catalogs/provider-identities.json',import.meta.url));
 await mkdir(dirname(output),{recursive:true});await writeFile(output,JSON.stringify(feed));
 console.log(JSON.stringify({nativeRows:feed.nativeRows.length,partnerRows:feed.partnerRows.length,bytes:Buffer.byteLength(JSON.stringify(feed))}));
}
