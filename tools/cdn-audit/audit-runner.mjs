import { Probe } from './probe.mjs';
import { writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';

export async function runAudit(profile, args = process.argv.slice(2)) {
  const option = key => { const i = args.indexOf(key); return i < 0 ? undefined : args[i + 1]; };
  if (args.includes('--help')) {
    console.log(`audit-${profile}.mjs [--base-url https://net52.cc] [--session-file PRIVATE/session.json] [--private-dir PRIVATE] [--report report.json] [--decode-sample]\nNormal provider authorization only; no modified CDN signatures. Default: Mr. Robot S1E1 and Smallville S4E8.`);
    return;
  }
  const probe = new Probe({ appProfile: profile, baseUrl: option('--base-url') || 'https://net52.cc',
    deadlineAt: Date.now() + 150000, triggerBaseUrl: option('--trigger-base-url'), sessionFile: option('--session-file'),
    privateDir: option('--private-dir'), requestTimeoutMs: 8000, handshakeTimeoutMs: 58000,
    segmentCount: 2, decodeSample: args.includes('--decode-sample') });
  await probe.init();
  const targets = [{ title: 'Mr. Robot', year: 2015, season: 1, episode: 1 }, { title: 'Smallville', year: 2001, season: 4, episode: 8 }];
  const runs = []; const compatibility = []; const startedAt = Date.now(); let stopped;
  const stopErrors = new Set(['rate_limited', 'home_unavailable', 'addhash_missing', 'verification_cookie_missing', 'proxy_connect_failed', 'audit_budget_exceeded']);
  for (const phase of ['first_pass', 'warm_repeat']) {
    for (const target of targets) {
      if (Date.now() - startedAt > 150000 || stopped) break;
      const run = await probe.runTitle(target, phase); runs.push(run);
      console.log(`${profile}: ${target.title} S${target.season}E${target.episode}, ${phase}: ${run.success ? 'validated media bytes' : run.error}, ${run.totalMs}ms, handshakes=${run.handshakes}`);
      if (run.error === 'cdn_route_rejected' && phase === 'first_pass') {
        const url = probe.lastAttemptedMediaUrl;
        const extraHeaders = profile === 'mobile' ? {
          'Accept': '*/*', 'Accept-Language': 'en-US,en;q=0.9',
          'sec-ch-ua': '\"Not(A:Brand\";v=\"99\", \"Android WebView\";v=\"133\", \"Chromium\";v=\"133\"',
          'sec-ch-ua-mobile': '?1', 'sec-ch-ua-platform': '\"Android\"'
        } : {};
        const before = probe.events.length;
        try { await probe.request('exact_app_headers', url, { extraHeaders }); }
        catch (_) { /* Report the status; never edit the provider's signed token. */ }
        compatibility.push({ title: target.title, year: target.year, purpose: 'retry unchanged issued URL with app headers', events: probe.events.slice(before) });
      }
      if (stopErrors.has(run.error)) stopped = run.error;
    }
  }
  const report = { schemaVersion: 1, profile, timestamp: new Date().toISOString(),
    authorization: 'normal provider handshake and unchanged provider-issued CDN URLs',
    scope: 'Node HTTP/HLS/media samples; does not measure Kotlin app rendering or Android first frame',
    cachedBehavior: profile === 'tv' ? 'retain direct video route plus declared audio metadata' : 'retain provider entry playlist plus OTT and episode cookie',
    runs, compatibility, skipped: targets.filter(t => !runs.some(r => r.title === t.title)).map(t => ({ ...t, reason: stopped || 'budget' })) };
  const reportPath = resolve(option('--report') || join(probe.privateDir, `audit-${profile}.json`));
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
  console.log(`Sanitized report: ${reportPath}`);
  if (runs.some(r => !r.success) || report.skipped.length) process.exitCode = 2;
  return report;
}
