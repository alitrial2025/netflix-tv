#!/usr/bin/env node
/** One controlled comparison using normal issued data and the public app master-token formula.
 * Never extracts signing secrets or guesses keys. Raw tokens remain private.
 */
import { Probe } from './probe.mjs';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { resolve } from 'node:path';

const args = process.argv.slice(2);
const value = key => { const i = args.indexOf(key); return i < 0 ? undefined : args[i + 1]; };
if (args.includes('--help')) {
  console.log('node tools/cdn-audit/compare-tokens.mjs --session-file /private/session.json --issued-result /private/last-result.json --private-dir /private/comparison --report /public/comparison.json');
  process.exit(0);
}
const sessionFile = value('--session-file'); const issuedFile = value('--issued-result');
if (!sessionFile || !issuedFile) { console.error('session_file_and_issued_result_required'); process.exit(1); }
const session = JSON.parse(await readFile(sessionFile, 'utf8'));
const result = JSON.parse(await readFile(issuedFile, 'utf8'));
const source = new URL(result.url); const issued = source.searchParams.get('in');
if (!issued) throw new Error('issued_signature_missing');
const parts = issued.split('::');
if (parts.length < 4 || !/^\d+$/.test(parts[2])) throw new Error('issued_format_not_supported_for_reconstruction');
const contentId = source.pathname.match(/\/files\/([^/]+)/)?.[1];
if (!contentId) throw new Error('content_id_missing');
const reconstructed = parts.join('::');
const ts = String(Math.floor(Date.now() / 1000));
// This formula is already in the app for the PROVIDER master endpoint. It is
// deliberately tested once against the CDN; it is not claimed as a CDN signer.
const clientMaster = `${parts[0]}::${createHash('md5').update(ts + contentId).digest('hex')}::${ts}::ek::m`;
const p = new Probe({ baseUrl: session.baseUrl || `https://${session.domain}`, sessionFile, privateDir: value('--private-dir'), decodeSample: true });
await p.init();
const variants = [
  { name: 'original_provider_signature', signature: issued, decode: true },
  { name: 'constructed_from_issued_fields', signature: reconstructed, decode: true },
  { name: 'public_client_master_formula_at_cdn', signature: clientMaster, decode: false }
];
const comparisons = [];
for (const variant of variants) {
  const start = performance.now(); const before = p.events.length; const url = new URL(source); url.searchParams.set('in', variant.signature);
  let evidence; let error;
  try {
    if (variant.decode) evidence = await p.verifyMedia(url.href);
    else { const response = await p.request(variant.name, url.href); evidence = { acceptedAsHls: response.kind === 'hls' }; }
  } catch (e) { error = /^[a-z_]+$/.test(e.message) ? e.message : 'probe_failed'; }
  const comparison = { name: variant.name, byteIdenticalToIssuedSignature: variant.signature === issued, tokenShape: variant.signature.split('::').slice(3), elapsedMs: Math.round(performance.now() - start), success: !error, ...(error ? { error } : evidence), events: p.events.slice(before) };
  comparisons.push(comparison); console.log(`${variant.name}: ${error || (evidence.decodedSampleFrame ? 'HLS, media samples and decoded frame verified' : `acceptedAsHls=${evidence.acceptedAsHls}`)}; ${comparison.elapsedMs}ms`);
  if (error === 'rate_limited') break;
}
const report = { timestamp: new Date().toISOString(), contentId, scope: 'Byte-identical reconstruction of issued fields is token reuse. Independent CDN signature issuance is not demonstrated.', comparisons };
await writeFile(resolve(value('--report') || `${p.privateDir}/comparison-report.json`), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
if (comparisons.slice(0, 2).some(r => !r.success)) process.exitCode = 2;
