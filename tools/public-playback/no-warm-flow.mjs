#!/usr/bin/env node
// Node 20+: bounded, cookie-free metadata -> issued playlist -> HLS -> media samples.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

export const TARGETS = [
  { ott: 'nf', title: 'Glass Onion: A Knives Out Mystery', year: 2022, type: 'movie' },
  { ott: 'pv', title: 'Road House', year: 2024, type: 'movie' },
  { ott: 'pv', title: 'Mr. Robot', year: 2015, type: 'tv', season: 1, episode: 1 },
  { ott: 'hs', title: 'Special Ops', year: 2020, type: 'tv', season: 1, episode: 1 },
  { ott: 'dp', title: 'Andor', year: 2022, type: 'tv', season: 1, episode: 1 },
  { ott: 'hb', title: 'The Penguin', year: 2024, type: 'tv', season: 1, episode: 1 },
  { ott: 'atp', title: 'Slow Horses', year: 2022, type: 'tv', season: 1, episode: 1 },
  { ott: 'pm', title: 'Top Gun: Maverick', year: 2022, type: 'movie' },
  { ott: 'pc', title: 'Twisted Metal', year: 2023, type: 'tv', season: 1, episode: 1 },
  { ott: 'hlu', title: 'Palm Springs', year: 2020, type: 'movie' }
];

// Explicit input from prior checks of official Prime season links, not derived IDs.
export const VERIFIED_SEASONS = [
  ['0L52QDYY6OG738LB7ILP0VB7R4', 1], ['0SJJSQE04USSW0CM5BMESSR1IG', 2],
  ['0IZIIF0YZ4HGFICLLYB4SAHQDN', 3], ['0FGILMYR4HOOKYY2K9NH7UE378', 4]
].map(([seasonId, season]) => ({ ott: 'pv', showId: '0L52QDYY6OG738LB7ILP0VB7R4',
  season, seasonId, source: `https://www.primevideo.com/detail/${seasonId}` }));

const OTTS = new Set(['nf', 'pv', 'hs', 'dp', 'hb', 'atp', 'pm', 'pc', 'hlu']);
const prefix = ott => ott === 'nf' ? '/mobile' : `/mobile/${ott}`;
const norm = s => String(s ?? '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '');
const num = s => Number(String(s ?? '').match(/\d+/)?.[0]);
const idOf = r => String(r?.id ?? r?.Id ?? r?.sid ?? '');
export class FlowError extends Error {
  constructor(code, stage) { super(code); this.code = code; this.stage = stage; }
}
const fail = (code, stage) => { throw new FlowError(code, stage); };
export function mediaKind(bytes) {
  for (let i = 0; i < Math.min(188, bytes.length); i++)
    if (i + 376 < bytes.length && [i, i + 188, i + 376].every(j => bytes[j] === 0x47)) return 'mpeg_ts';
  if (bytes.length >= 12 && ['ftyp', 'styp', 'moof', 'sidx'].includes(bytes.toString('ascii', 4, 8))) return 'iso_bmff';
  if (bytes.length > 3 && (bytes.toString('ascii', 0, 3) === 'ID3' || bytes[0] === 0xff && (bytes[1] & 0xf0) === 0xf0)) return 'aac';
  return 'unrecognized';
}

// Exact default public master-request construction already used by both apps.
// Apply only to the returned same-origin provider HLS source, never a CDN signature.
export function appMasterRequest(source, contentId, baseUrl, timestamp = Math.floor(Date.now() / 1000)) {
  const url = new URL(source); const base = new URL(baseUrl);
  if (url.origin !== base.origin || !/^\/mobile\/(?:[a-z]+\/)?hls\//.test(url.pathname))
    return { url: source, constructed: false };
  if (url.searchParams.has('in') && !url.searchParams.get('in').startsWith('unknown'))
    return { url: source, constructed: false };
  const ts = String(timestamp);
  const hash = createHash('md5').update(ts + contentId).digest('hex');
  url.searchParams.set('in', `235ca31540ab8d90fcef4a00de8a247c::${hash}::${ts}::ek::m`);
  return { url: url.href, constructed: true };
}

export class NoWarmFlow {
  constructor({ baseUrl = 'https://net52.cc', seasonMappings = VERIFIED_SEASONS,
    requestTimeoutMs = 10000, titleTimeoutMs = 45000, allowLocalFixtures = false, appClientMode = false } = {}) {
    this.base = new URL(baseUrl).origin; this.seasonMappings = seasonMappings;
    this.requestTimeoutMs = requestTimeoutMs; this.titleTimeoutMs = titleTimeoutMs;
    this.allowLocalFixtures = allowLocalFixtures; this.events = []; this.stage = 'search';
    this.rateLimited = false; this.requestCount = 0;
    this.appClientMode = appClientMode;
  }
  async request(stage, raw, binary = false) {
    this.stage = stage;
    const url = new URL(raw, this.base);
    if (url.username || url.password || (!this.allowLocalFixtures && url.protocol !== 'https:')) fail('unsupported_url', stage);
    const remaining = this.deadline - Date.now();
    if (remaining <= 0) fail('title_budget_exceeded', stage);
    if (++this.requestCount > 32) fail('title_request_budget_exceeded', stage);
    const headers = { 'User-Agent': 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0',
      'Accept': '*/*', 'X-Requested-With': binary || url.origin !== this.base ? 'app.netmirror.netmirrornew' : 'XMLHttpRequest',
      'Referer': this.base + '/', 'Origin': this.base };
    if (binary) headers.Range = 'bytes=0-65535';
    // Node fetch has no browser cookie jar. Never add Cookie, Authorization or client certificates.
    const event = { stage, host: url.host, path: url.pathname, queryKeys: [...new Set(url.searchParams.keys())] };
    this.events.push(event); const start = Date.now();
    try {
      const response = await fetch(url, { headers, redirect: 'error', credentials: 'omit',
        signal: AbortSignal.timeout(Math.min(this.requestTimeoutMs, remaining)) });
      event.status = response.status;
      const cap = binary ? 65536 : 1048576;
      const reader = response.body?.getReader(); const chunks = []; let size = 0;
      if (reader) {
        while (true) {
          const { done, value } = await reader.read(); if (done) break;
          const remainingBytes = cap - size;
          chunks.push(Buffer.from(value.subarray(0, remainingBytes))); size += Math.min(value.length, remainingBytes);
          if (size >= cap) { await reader.cancel(); if (!binary) fail('response_too_large', stage); break; }
        }
      }
      const bytes = Buffer.concat(chunks); const body = bytes.toString('utf8'); event.sampledBytes = bytes.length;
      if (response.status === 429 || /\/files\/220884(?:[/?\s]|$)|too many requests|rate[_ -]?limit/i.test(body)) {
        this.rateLimited = true; fail('rate_limited', stage);
      }
      if ([401, 403].includes(response.status)) fail('authorization_required', stage);
      if (response.status === 404) fail('endpoint_not_found', stage);
      if (!response.ok) fail('http_error', stage);
      let data; if (!binary) { try { data = JSON.parse(body); } catch {} }
      if (/invalid user|login required|session expired|only valid users allowed/i.test(body)) fail('authorization_required', stage);
      return { data, body, bytes, url: url.href };
    } catch (error) {
      event.error = error instanceof FlowError ? error.code : error.name === 'TimeoutError' ? 'request_timeout' : 'transport_error';
      if (error instanceof FlowError) throw error;
      fail(event.error, stage);
    } finally { event.elapsedMs = Date.now() - start; }
  }
  async json(stage, path, params) {
    const url = new URL(path, this.base); url.search = new URLSearchParams(params);
    const result = await this.request(stage, url.href);
    if (result.data == null) fail('json_expected', stage);
    return result.data;
  }
  async search(target) {
    const paths = target.ott === 'nf' ? ['/search.php', '/mobile/search.php'] : [prefix(target.ott) + '/search.php'];
    for (const path of paths) {
      const data = await this.json('search', path, { s: target.title });
      if (data.head === 'Top Searches' || data.status === 'n') continue;
      let matches = (Array.isArray(data) ? data : data.searchResult || [])
        .filter(row => {
          const title = row.t || row.title || row.T || row.Title;
          const suffix = String(title ?? '').match(/\s*\((\d{4})\)\s*$/);
          const comparable = suffix && Number(suffix[1]) === target.year ? title.slice(0, suffix.index) : title;
          return norm(comparable) === norm(target.title);
        });
      matches = matches.filter(row => !(row.y || row.year || row.Y || row.Year) || Number(row.y || row.year || row.Y || row.Year) === target.year);
      if (target.nativeId) matches = matches.filter(row => idOf(row) === target.nativeId);
      if (matches.length > 1) fail('ambiguous_title_identity', 'search');
      if (matches.length === 1 && idOf(matches[0])) return { id: idOf(matches[0]),
        yearVerified: Number(matches[0].y || matches[0].year || matches[0].Y || matches[0].Year) === target.year };
    }
    fail('title_not_found_or_query_rejected', 'search');
  }
  async episode(target, show, run) {
    const mapping = this.seasonMappings.find(x => x.ott === target.ott && x.showId === show.id && x.season === target.season);
    let seasonId;
    if (mapping) { seasonId = mapping.seasonId; run.seasonSource = 'verified_mapping_input'; run.mappingSource = mapping.source; }
    else {
      const data = await this.json('season_catalog', prefix(target.ott) + '/post.php', { id: show.id });
      const seasons = data.season || data.seasons || [];
      const direct = (data.episodes || []).find(x => num(x.s || x.season || x.s_num) === target.season && num(x.ep || x.episode || x.e) === target.episode);
      if (direct && idOf(direct)) return idOf(direct);
      const row = seasons.find(x => num(x.s || x.season || x.name || x.title) === target.season);
      seasonId = idOf(row); run.seasonSource = 'provider_catalog';
      if (!seasonId) fail('season_id_unavailable', 'season_catalog');
    }
    run.seasonId = seasonId;
    for (let page = 1; page <= 5; page++) {
      const data = await this.json('episodes', prefix(target.ott) + '/episodes.php',
        { s: seasonId, series: show.id, ...(page > 1 ? { page: String(page) } : {}) });
      for (const ep of data.episodes || []) {
        const label = ep.s || ep.season || ep.s_num;
        if (label && num(label) !== target.season) fail('season_label_mismatch', 'episodes');
        if (num(ep.ep || ep.episode || ep.e) === target.episode && idOf(ep)) {
          run.episodeTitle = ep.t || ep.title; return idOf(ep);
        }
      }
      if (String(data.nextPageShow) !== '1') break;
    }
    fail('requested_episode_missing', 'episodes');
  }
  async discover(target, contentId, run) {
    const data = await this.json('playlist', prefix(target.ott) + '/playlist.php',
      { id: contentId, t: target.title, tm: String(Math.floor(Date.now() / 1000)) });
    const item = Array.isArray(data) ? data[0] : data;
    const source = item?.sources?.find(x => x.file)?.file;
    if (!source) fail('issued_playlist_url_missing', 'playlist');
    let url = new URL(source, this.base);
    if (this.appClientMode) {
      const request = appMasterRequest(url.href, contentId, this.base);
      url = new URL(request.url); run.tokenGeneration = request.constructed;
      if (request.constructed) run.masterRequestMode = 'existing_app_public_ek_m';
    }
    if ([...url.searchParams.values()].some(x => /^unknown(?:::|$)/i.test(x))) fail('issued_authorization_missing', 'playlist');
    // Preserve all issued CDN URLs; only same-origin placeholder masters use the app flow.
    return url.href;
  }
  async playlist(url, stage) {
    const result = await this.request(stage, url);
    if (!result.body.trimStart().startsWith('#EXTM3U')) fail('hls_expected', stage);
    if (/in=unknown/i.test(result.body)) fail('issued_authorization_missing', stage);
    if (/#EXT-X-KEY:[^\r\n]*METHOD=(?!NONE)[A-Z0-9-]+/.test(result.body)) fail('encrypted_media_requires_normal_player', stage);
    return result;
  }
  async traverse(url, stage, audio = false) {
    let selectedAudio;
    for (let depth = 0; depth < 4; depth++) {
      const result = await this.playlist(url, stage);
      const lines = result.body.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
      if (result.body.includes('#EXT-X-STREAM-INF:')) {
        const index = lines.findIndex(x => x.startsWith('#EXT-X-STREAM-INF:'));
        const next = lines[index + 1]; if (!next || next.startsWith('#')) fail('variant_url_missing', stage);
        const group = lines[index].match(/AUDIO="([^"]+)"/)?.[1];
        const audioLines = lines.filter(x => x.startsWith('#EXT-X-MEDIA:') && x.includes('TYPE=AUDIO') &&
          (!group || x.includes(`GROUP-ID="${group}"`)));
        const track = audioLines.find(x => x.includes('DEFAULT=YES')) || audioLines[0];
        const audioUri = track?.match(/URI="([^"]+)"/)?.[1];
        if (audioUri) selectedAudio = new URL(audioUri, url).href;
        url = new URL(next, url).href; continue;
      }
      if (!result.body.includes('#EXTINF:')) fail('media_playlist_missing', stage);
      const init = result.body.match(/#EXT-X-MAP:[^\r\n]*URI="([^"]+)"/)?.[1];
      if (init) {
        const r = await this.request(audio ? 'audio_init' : 'video_init', new URL(init, url).href, true);
        if (mediaKind(r.bytes) !== 'iso_bmff') fail('invalid_media_init', stage);
      }
      const segment = lines.find(x => !x.startsWith('#'));
      if (!segment) fail('segment_url_missing', stage);
      const r = await this.request(audio ? 'audio_sample' : 'video_sample', new URL(segment, url).href, true);
      const kind = mediaKind(r.bytes);
      if (!(audio ? ['mpeg_ts', 'iso_bmff', 'aac'] : ['mpeg_ts', 'iso_bmff']).includes(kind)) fail('invalid_media_sample', stage);
      return { kind, bytes: r.bytes.length, selectedAudio };
    }
    fail('manifest_depth_exceeded', stage);
  }
  async run(target) {
    const run = { ...target, success: false, handshakes: 0, authenticationCookiesSent: false,
      tokenGeneration: false, guessedIds: false }; this.events = []; this.requestCount = 0;
    const start = Date.now(); this.deadline = start + this.titleTimeoutMs;
    try {
      if (!OTTS.has(target.ott) || !['movie', 'tv'].includes(target.type) || !target.title ||
          target.type === 'tv' && !(target.season > 0 && target.episode > 0)) fail('invalid_target', 'input');
      const show = await this.search(target); run.providerId = show.id; run.yearVerified = show.yearVerified;
      const contentId = target.type === 'movie' ? show.id : await this.episode(target, show, run);
      run.contentId = contentId;
      const route = await this.discover(target, contentId, run);
      const video = await this.traverse(route, 'video_manifest');
      run.videoSample = { kind: video.kind, bytes: video.bytes };
      if (video.selectedAudio) {
        const a = await this.traverse(video.selectedAudio, 'audio_manifest', true);
        run.audioSample = { kind: a.kind, bytes: a.bytes };
      }
      run.success = true; run.evidence = 'hls_and_bounded_media_container_sample';
    } catch (error) { run.failedStage = error.stage || this.stage; run.error = error.code || 'audit_error'; }
    run.elapsedMs = Date.now() - start; run.events = this.events;
    return run;
  }
}

async function main() {
  const args = process.argv.slice(2); const option = k => { const i = args.indexOf(k); return i < 0 ? undefined : args[i + 1]; };
  if (args.includes('--help')) {
    console.log('node no-warm-flow.mjs [--targets targets.json] [--season-map mappings.json] [--base-url https://net52.cc] [--report result.json] [--app-client-mode]\nNo handshake, session-file, authentication cookies or ID guessing. --app-client-mode uses the existing public app master-request parameters for same-origin placeholders; issued CDN signatures stay unchanged. Default: 10 mixed titles across 9 configured catalogs.'); return;
  }
  const targets = option('--targets') ? JSON.parse(await readFile(option('--targets'), 'utf8')) : TARGETS;
  const seasonMappings = option('--season-map') ? JSON.parse(await readFile(option('--season-map'), 'utf8')) : VERIFIED_SEASONS;
  const flow = new NoWarmFlow({ baseUrl: option('--base-url'), seasonMappings, appClientMode: args.includes('--app-client-mode') });
  const runs = []; const skipped = []; const start = Date.now();
  for (const target of targets) {
    if (flow.rateLimited || Date.now() - start > 300000) { skipped.push({ ...target, reason: flow.rateLimited ? 'rate_limited' : 'overall_budget' }); continue; }
    const run = await flow.run(target); runs.push(run);
    console.log(`${target.ott}: ${target.title} (${target.type}) -> ${run.success ? 'media sample verified' : run.failedStage + ': ' + run.error}`);
    await new Promise(r => setTimeout(r, 250));
  }
  const report = { schemaVersion: 1, timestamp: new Date().toISOString(),
    scope: 'Cold no-authentication HTTP flow; container samples do not prove Android playback or decoded first frame.',
    inputs: 'Prime Mr. Robot season mappings are verified inputs from an earlier official-source check, not discovered during this run.',
    successCount: runs.filter(r => r.success).length, titleCount: targets.length, handshakes: 0,
    authenticationCookiesSent: false, appClientMode: flow.appClientMode,
    tokenGeneration: runs.some(r => r.tokenGeneration), issuedCdnSignaturesModified: false, runs, skipped };
  const output = resolve(option('--report') || 'no-warm-flow-report.json'); await mkdir(dirname(output), { recursive: true });
  await writeFile(output, JSON.stringify(report, null, 2));
  console.log(`Verified media samples: ${report.successCount}/${targets.length}. Report: ${output}`);
  if (report.successCount !== targets.length) process.exitCode = 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
