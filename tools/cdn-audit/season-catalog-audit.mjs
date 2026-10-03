#!/usr/bin/env node
// Collect only season IDs returned for the requested show by a normal session.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { Probe } from './probe.mjs';

export function validateIdentity(identity) {
  // Provider IDs are opaque: Netflix is numeric, Prime uses case-sensitive ASINs.
  if (typeof identity.showId !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(identity.showId) ||
      !['nf', 'pv', 'hs', 'dp', 'hb', 'atp', 'pm', 'pc', 'hlu'].includes(identity.ott))
    throw new Error('invalid_identity');
  return identity;
}

export function parseSeasons(payload) {
  if (payload?.error || payload?.status === 'n') throw new Error('catalog_rejected');
  const rows = payload?.season ?? payload?.seasons;
  if (!Array.isArray(rows) || !rows.length) throw new Error('seasons_missing');
  const seasons = rows.map(row => {
    const id = String(row.id ?? row.Id ?? row.sid ?? '');
    const label = String(row.s || row.season || row.name || row.title || '');
    const match = label.match(/^(?:season\s*|s\s*)?(\d+)$/i);
    if (!id || !match) throw new Error('season_identity_missing');
    return { number: Number(match[1]), id };
  });
  if (new Set(seasons.map(s => s.number)).size !== seasons.length ||
      new Set(seasons.map(s => s.id)).size !== seasons.length) throw new Error('duplicate_season_identity');
  return seasons.sort((a, b) => a.number - b.number);
}

export function validateEpisodes(payload, season) {
  if (payload?.error || payload?.status === 'n') throw new Error('episodes_rejected');
  if (!Array.isArray(payload?.episodes) || !payload.episodes.length) throw new Error('episodes_missing');
  return payload.episodes.map(row => {
    const id = String(row.id ?? row.Id ?? '');
    const tag = String(row.s || row.season || row.s_num || '');
    const n = tag.match(/\d+/)?.[0];
    const ep = String(row.ep || row.episode || row.e || '').match(/\d+/)?.[0];
    if (n && Number(n) !== season.number) throw new Error('season_mismatch');
    if (!id || !ep) throw new Error('episode_identity_missing');
    return { id, number: Number(ep), title: String(row.t || row.title || '') };
  });
}

export function loadCatalog(catalog, { baseUrl, showId, ott }, now = Date.now()) {
  if (catalog.schema !== 1 || catalog.baseUrl !== baseUrl || catalog.showId !== showId || catalog.ott !== ott)
    throw new Error('cache_identity_mismatch');
  if (!Number.isFinite(catalog.fetchedAt) || now < catalog.fetchedAt || now - catalog.fetchedAt > 86400000)
    throw new Error('cache_expired');
  parseSeasons({ seasons: catalog.seasons.map(s => ({ id: s.id, s: s.number })) });
  return catalog;
}

async function main() {
  const args = process.argv.slice(2);
  const value = (key, fallback) => { const i = args.indexOf(key); return i < 0 ? fallback : args[i + 1]; };
  if (args.includes('--help')) {
    console.log('node tools/cdn-audit/season-catalog-audit.mjs --catalog PATH --report PATH [--session-file PATH] [--reuse-cache] [--show-id ID] [--ott nf] [--base-url https://net52.cc]');
    return;
  }
  const identity = { baseUrl: new URL(value('--base-url', 'https://net52.cc')).origin,
    showId: value('--show-id', '70155584'), ott: value('--ott', 'nf') };
  validateIdentity(identity);
  if (identity.ott !== 'nf' && !args.includes('--show-id')) throw new Error('show_id_required');
  const catalogPath = resolve(value('--catalog', 'season-catalog.json'));
  const reportPath = resolve(value('--report', 'season-catalog-report.json'));
  const report = { ...identity, timestamp: new Date().toISOString(), success: false, handshakes: 0, events: [] };
  let p;
  try {
    let catalog;
    if (args.includes('--reuse-cache')) {
      catalog = loadCatalog(JSON.parse(await readFile(catalogPath, 'utf8')), identity);
      report.mode = 'offline_cache';
    } else {
      p = new Probe({ baseUrl: identity.baseUrl, sessionFile: value('--session-file'),
        privateDir: value('--private-dir'), deadlineAt: Date.now() + 100000 });
      await p.init(); await p.ensureSession();
      if (p.base !== identity.baseUrl) throw new Error('provider_origin_changed');
      const prefix = identity.ott === 'nf' ? '/mobile' : `/mobile/${identity.ott}`;
      const post = await p.jsonCandidates('season_catalog', [`${prefix}/post.php`], identity.ott,
        { id: identity.showId, t: Math.floor(Date.now() / 1000) });
      const seasons = parseSeasons(post);
      if (seasons.length > 30) throw new Error('season_request_budget_exceeded');
      for (const season of seasons) {
        const data = await p.jsonCandidates('season_first_page', [`${prefix}/episodes.php`], identity.ott,
          { s: season.id, series: identity.showId, t: Math.floor(Date.now() / 1000) });
        season.episodes = validateEpisodes(data, season);
        season.hasMorePages = String(data.nextPageShow) === '1';
      }
      catalog = { schema: 1, ...identity, fetchedAt: Date.now(), source: 'normal_authenticated_show_catalog', seasons };
      await mkdir(dirname(catalogPath), { recursive: true });
      await writeFile(catalogPath, JSON.stringify(catalog, null, 2));
      report.mode = 'normal_session';
    }
    report.success = true;
    report.seasons = catalog.seasons.map(s => ({ number: s.number, id: s.id,
      sampledEpisodes: s.episodes.length, hasMorePages: s.hasMorePages }));
    report.completeEpisodeCatalog = catalog.seasons.every(s => !s.hasMorePages);
  } catch (error) {
    report.error = /^[a-z_]+$/.test(error.message) ? error.message : 'audit_failed';
    process.exitCode = 1;
  } finally {
    report.handshakes = p?.handshakes ?? 0; report.events = p?.events ?? [];
    await mkdir(dirname(reportPath), { recursive: true });
    await writeFile(reportPath, JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
