#!/usr/bin/env node
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {dirname} from 'node:path';
import {enrichNetflixCatalog,initialNetflixState} from './netflix-catalog.mjs';
const args = process.argv.slice(2), option = key => args.includes(key) ? args[args.indexOf(key)+1] : undefined;
const input = option('--native'); if (!input) throw Error('A native catalog input is required');
const statePath = option('--state') ?? 'catalogs/netflix-metadata-state.json';
const output = option('--output') ?? input, stateOutput = option('--state-output') ?? statePath;
let previous; try { previous = JSON.parse(await readFile(statePath,'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw Error('Cannot read previous Netflix metadata state'); previous = initialNetflixState(); }
let apiKey = process.env.TMDB_API_KEY;
if (!apiKey) {
  try { apiKey = (await readFile(option('--tmdb-config') ?? '.env.example','utf8')).match(/^TMDB_API_KEY\s*=\s*([^\r\n#]+)/m)?.[1].trim(); }
  catch { /* Missing optional configuration must not break the existing feed refresh. */ }
}
async function transport(url,{signal,maximumBytes}) {
  const parsed = new URL(url);
  if (parsed.protocol !== 'https:' || !['www.netflix.com','api.themoviedb.org'].includes(parsed.hostname) || parsed.username || parsed.password)
    throw Error('Unexpected metadata origin');
  const response = await fetch(url,{credentials:'omit',redirect:'error',headers:{'User-Agent':'NetflixProPublicMetadata/1.0','Accept-Language':'en-US,en;q=0.9'},
    signal:AbortSignal.any([signal,AbortSignal.timeout(8000)])});
  if (!response.ok) { await response.body?.cancel(); throw Error('Public metadata unavailable'); }
  if (Number(response.headers.get('content-length')) > maximumBytes) { await response.body?.cancel(); throw Error('Public metadata too large'); }
  const chunks = []; let bytes = 0;
  for await (const chunk of response.body) {
    bytes += chunk.length;
    if (bytes > maximumBytes) { await response.body.cancel().catch(() => {}); throw Error('Public metadata too large'); }
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString('utf8');
}
const result = await enrichNetflixCatalog(JSON.parse(await readFile(input,'utf8')),previous,{transport,apiKey});
for (const [path,value] of [[output,result.native],[stateOutput,result.state]]) {
  await mkdir(dirname(path),{recursive:true}); await writeFile(path,JSON.stringify(value));
}
console.log(JSON.stringify(result.stats));
