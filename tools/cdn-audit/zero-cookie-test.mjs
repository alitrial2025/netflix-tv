#!/usr/bin/env node
// Compatibility entrypoint for the public flow used by the current playback audit.
// Reuses issuer-provided CDN URLs, validates TLS, and never guesses CDN signatures.
import { spawnSync } from 'node:child_process';
import { mkdtemp, writeFile, unlink, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('Set TMDB_API_KEY or pass --tmdb-config PATH. Optional: --targets PATH --report PATH. Default target: Smallville S4E8.');
} else {
  const scratch = await mkdtemp(join(tmpdir(),'npro-public-audit-'));
  const targets = join(scratch,'targets.json');
  const forwarded = [...args];
  if (!args.includes('--targets')) {
    await writeFile(targets,JSON.stringify([{ott:'nf',title:'Smallville',year:2001,type:'tv',season:4,episode:8,tmdbId:'4607'}]),{mode:0o600});
    forwarded.push('--targets',targets);
  }
  const report = args.includes('--report') ? args[args.indexOf('--report')+1] : join(scratch,'report.json');
  if (!args.includes('--report')) forwarded.push('--report',report);
  try {
    const result = spawnSync(process.execPath,[fileURLToPath(new URL('../public-playback/public-catalog-flow.mjs',import.meta.url)),...forwarded],{stdio:'inherit'});
    if (result.error) throw result.error;
    process.exitCode = result.status ?? 1;
    console.log('Sanitized public audit report:',report);
  } finally {
    await unlink(targets).catch(()=>{});
    if (args.includes('--report')) await rmdir(scratch).catch(()=>{});
  }
}
