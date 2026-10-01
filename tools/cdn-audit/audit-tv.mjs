#!/usr/bin/env node
/** TV: normal handshake, exact episodes, direct CDN, cached audio, bounded samples. */
import { runAudit } from './audit-runner.mjs';
runAudit('tv').catch(() => { console.error('TV audit failed; inspect the sanitized stage report.'); process.exitCode = 1; });
