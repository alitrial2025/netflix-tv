#!/usr/bin/env node
// Compatibility entrypoint: use the actual show catalog, never nearby numeric IDs.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const args = process.argv.slice(2);
if (!args.length || args.includes('--help')) {
  console.log('node tools/cdn-audit/season-probe.mjs --catalog PATH --report PATH [--session-file PATH | --reuse-cache] [--show-id ID] [--ott nf]');
  console.log('Delegates to the bounded season catalog audit. IDs are never guessed from numeric proximity.');
} else {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./season-catalog-audit.mjs',import.meta.url)),...args], {stdio:'inherit'});
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
}
