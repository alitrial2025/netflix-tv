#!/usr/bin/env node
/** Normal DirectCDN handshake/search/episode/playlist audit. Node 20+ and curl required.
 * Cookies, signed URLs and segment samples stay in the private output directory.
 * Public report contains stage timings/statuses only. No challenge/access bypass.
 */
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, writeFile, readFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';

const UA = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';
const HASH = '235ca31540ab8d90fcef4a00de8a247c';
const TTL = 10 * 3600_000 - 60_000;
const OTTS = ['nf', 'pv', 'hs', 'dp', 'hb', 'atp', 'pm', 'pc', 'hlu'];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const prefix = ott => ott === 'nf' ? '/mobile' : `/mobile/${ott}`;
const paths = (ott, endpoint) => [...new Set([`${prefix(ott)}/${endpoint}`, `/mobile/${endpoint}`, `/${endpoint}`])];
const normalize = title => title.toLowerCase().replace(/[^a-z0-9]/g, '');
const number = value => Number(String(value ?? '').match(/\d+/)?.[0]);
const masterToken = id => { const ts = String(Math.floor(Date.now() / 1000)); return `${HASH}::${createHash('md5').update(ts + id).digest('hex')}::${ts}::ek::m`; };
export function signatureExpiry(url, fetchedAt = Date.now()) {
  const raw = new URL(url).searchParams.get('in'); const fields = raw?.split('::');
  if (!fields || fields.length < 4 || !/^\d+$/.test(fields[2])) return undefined;
  const issued = Number(fields[2]) * 1000;
  if (!Number.isSafeInteger(issued) || issued <= 0 || issued - fetchedAt > 3600000) return 0;
  return Math.min(issued, fetchedAt) + 10 * 3600000;
}
export function classify(body, status) {
  const text = String(body);
  if (status === 429 || /\/files\/220884(?:[/?\s]|$)|rate[_ -]?limit|too many requests/i.test(text)) return 'rate_limited';
  if ([401, 403].includes(status) || (text.trimStart().startsWith('#EXTM3U') && /in=unknown/i.test(text)) || /session expired|token expired|invalid token|login required|only valid users allowed/i.test(text)) return 'session_rejected';
  if (status < 200 || status >= 300) return 'http_error';
  if (text.trimStart().startsWith('<')) return 'html';
  if (text.trimStart().startsWith('#EXTM3U')) return 'hls';
  try { JSON.parse(text); return 'json'; } catch { return 'other'; }
}
export function segmentKind(bytes) {
  // Check repeated sync bytes, not merely an HTTP200 or one leading0x47.
  for (let offset = 0; offset < Math.min(188, bytes.length); offset++) {
    if (offset + 376 < bytes.length && [offset, offset + 188, offset + 376].every(i => bytes[i] === 0x47)) return 'mpeg_ts';
  }
  if (bytes.length >= 12 && ['ftyp', 'styp', 'moof', 'sidx'].includes(bytes.toString('ascii', 4, 8))) return 'iso_bmff';
  return 'unrecognized';
}
export function playlistEntries(body, url) {
  if (classify(body, 200) !== 'hls') throw new Error('not_hls');
  return body.split(/\r?\n/).map(s => s.trim()).filter(s => s && !s.startsWith('#')).map(s => new URL(s, url).href);
}
function safeEndpoint(raw) { const u = new URL(raw); return { host: u.host, path: u.pathname, queryKeys: [...new Set(u.searchParams.keys())] }; }
const safeError = error => error.code || (/^[a-z_0-9]+$/.test(error.message) ? error.message : 'transport_error');
async function runCurl(args, timeoutMs) {
  return await new Promise((done, reject) => {
    const proc = spawn('curl', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = ''; let err = ''; const timer = setTimeout(() => proc.kill('SIGKILL'), timeoutMs + 1500);
    proc.stdout.on('data', x => out += x); proc.stderr.on('data', x => err += x);
    proc.on('error', e => { clearTimeout(timer); reject(e); });
    proc.on('close', code => { clearTimeout(timer); done({ code, out, errorKind: /timed out/i.test(err) ? 'timeout' : /CONNECT tunnel/i.test(err) ? 'proxy_connect_failed' : code ? 'curl_transport_error' : null }); });
  });
}
async function decodeSample(path) {
  return await new Promise(resolve => {
    const proc = spawn('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-threads', '1', '-i', path, '-frames:v', '1', '-an', '-progress', 'pipe:1', '-f', 'null', '-'], { stdio: ['ignore', 'pipe', 'ignore'] });
    let output = ''; const timer = setTimeout(() => proc.kill('SIGKILL'), 5000);
    proc.stdout.on('data', x => output += x); proc.on('error', () => { clearTimeout(timer); resolve(false); });
    proc.on('close', code => { clearTimeout(timer); resolve(code === 0 && /frame=([1-9]\d*)/.test(output)); });
  });
}
export class Probe {
  constructor(options) { this.options = options; this.base = options.baseUrl; this.events = []; this.seq = 0; this.session = null; this.lookup = new Map(); this.episodes = new Map(); this.routes = new Map(); this.handshakes = 0; this.mediaMetadata = new Map(); this.currentShow = null; this.currentId = null; this.routeFetchedAt = new Map(); }
  async init() {
    this.privateDir = resolve(this.options.privateDir || await mkdtemp(join(tmpdir(), 'netflixpro-cdn-')));
    await mkdir(this.privateDir, { recursive: true, mode: 0o700 }); await chmod(this.privateDir, 0o700);
    this.jar = join(this.privateDir, 'cookies.txt'); await writeFile(this.jar, '', { mode: 0o600 });
    if (this.options.sessionFile) {
      const s = JSON.parse(await readFile(this.options.sessionFile, 'utf8'));
      if (s.addhashEncoded && s.tHashTEncoded && s.fetchedAt > 0 && Date.now() - s.fetchedAt >= 0 && Date.now() - s.fetchedAt < TTL) {
        this.session = s; this.base = s.baseUrl || `https://${s.domain}`;
      }
    }
  }
  async request(stage, url, { cookie, form, binary = false, extraHeaders = {}, timeoutMs = this.options.requestTimeoutMs || 8000 } = {}) {
    if (this.options.deadlineAt) {
      const remaining = this.options.deadlineAt - Date.now();
      if (remaining <= 0) throw new Error('audit_budget_exceeded');
      timeoutMs = Math.min(timeoutMs, remaining);
    }
    const started = performance.now(); const id = ++this.seq; const bodyFile = join(this.privateDir, `${id}-body`); const headFile = join(this.privateDir, `${id}-headers`);
    // Put sensitive headers and URLs in a0600 config, not process arguments.
    const config = join(this.privateDir, `${id}-curl.conf`);
    const quote = value => `"${String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r/g, '').replace(/\n/g, '')}"`;
    const headers = { 'User-Agent': UA, 'Referer': this.base + '/', 'Origin': this.base, 'X-Requested-With': (binary || new URL(url).origin !== this.base) ? 'app.netmirror.netmirrornew' : 'XMLHttpRequest', ...extraHeaders };
    if (cookie) headers.Cookie = cookie;
    let settings = `url = ${quote(url)}\n` + Object.entries(headers).map(([k, v]) => `header = ${quote(`${k}: ${v}`)}`).join('\n') + '\n';
    if (form) settings += `data = ${quote(form)}\nheader = "Content-Type: application/x-www-form-urlencoded; charset=UTF-8"\n`;
    if (binary) settings += 'range = "0-262143"\n';
    await writeFile(config, settings, { mode: 0o600 });
    const result = await runCurl(['--silent', '--show-error', '--location', '--max-redirs', '5', '--proto', '=http,https', '--proto-redir', '=http,https', '--max-time', String(timeoutMs / 1000), '--max-filesize', binary ? '2097152' : '4194304', '--cookie', this.jar, '--cookie-jar', this.jar, '--dump-header', headFile, '--output', bodyFile, '--write-out', '%{json}', '--config', config], timeoutMs);
    for (const path of [bodyFile, headFile, this.jar]) await chmod(path, 0o600).catch(() => {});
    let metrics = {}; try { metrics = JSON.parse(result.out); } catch {}
    const bytes = await readFile(bodyFile).catch(() => Buffer.alloc(0)); const body = bytes.toString('utf8');
    const headersText = await readFile(headFile, 'utf8').catch(() => '');
    const event = { stage, ...safeEndpoint(url), elapsedMs: Math.round(performance.now() - started), status: metrics.http_code || 0, bytes: bytes.length, connectMs: Math.round((metrics.time_connect || 0) * 1000), firstByteMs: Math.round((metrics.time_starttransfer || 0) * 1000), redirects: metrics.num_redirects || 0, kind: result.errorKind || (binary ? segmentKind(bytes) : classify(body, metrics.http_code || 0)) };
    if (event.kind === 'session_rejected' && new URL(url).origin !== this.base) event.kind = 'cdn_route_rejected';
    event.proxyBlocked = /x-mitmproxy-blocked-reason:/i.test(headersText);
    this.events.push(event);
    if (result.errorKind) throw new Error(result.errorKind);
    if (event.kind === 'rate_limited') throw new Error('rate_limited');
    if (event.kind === 'cdn_route_rejected') throw new Error('cdn_route_rejected');
    if (event.kind === 'session_rejected') throw new Error('session_rejected');
    return { ...event, body, bytes, headersText, privateBodyFile: bodyFile, url: metrics.url_effective || url };
  }
  async cookie(name) {
    const lines = (await readFile(this.jar, 'utf8')).split(/\r?\n/);
    const host = new URL(this.base).hostname;
    return lines.filter(x => x && (!x.startsWith('#') || x.startsWith('#HttpOnly_'))).map(x => x.replace(/^#HttpOnly_/, '').split('\t')).find(x => x[5] === name && (host === x[0].replace(/^\./, '') || host.endsWith(x[0])) && (!Number(x[4]) || Number(x[4]) * 1000 > Date.now()))?.[6] || '';
  }
  async ensureSession() {
    if (this.session && Date.now() - this.session.fetchedAt >= 0 && Date.now() - this.session.fetchedAt < TTL) return this.session;
    this.handshakes++;
    await writeFile(this.jar, '', { mode: 0o600 });
    const home = await this.request('handshake_home', this.base + '/mobile/home?app=1');
    if (home.status !== 200) throw new Error('home_unavailable');
    this.base = new URL(home.url).origin;
    const encoded = await this.cookie('addhash') || encodeURIComponent(home.body.match(/data-(?:hash|addhash|token)=["']([^"']+)["']/)?.[1] || home.body.match(/(?:var|window\.)addhash\s*=\s*["']([^"']+)["']/)?.[1] || '');
    if (!encoded) throw new Error('addhash_missing');
    const raw = decodeURIComponent(encoded); const query = home.body.match(/(?:var\s+|window\.)?Qury\s*=\s*["']([^"']+)["']/)?.[1] || 'hee5';
    const subdomain = home.body.match(/(?:var\s+|window\.)?Vsite2?\s*=\s*["']([^"']+)["']/)?.[1] || 'userver';
    const verify = home.body.match(/["']\/(?:mobile\/)?(verify\d*\.php)["']/)?.[1] || 'verify2.php';
    const triggerBase = this.options.triggerBaseUrl || `https://${subdomain}.${new URL(this.base).hostname}`;
    await this.request('handshake_trigger', `${triggerBase}/?${encodeURIComponent(query)}=${encodeURIComponent(raw)}&a=y&t=${Math.random()}`).catch(error => { if (['rate_limited', 'session_rejected'].includes(error.message)) throw error; });
    const pollingStarted = performance.now(); let hash = '';
    for (let attempt = 1; attempt <= 40 && performance.now() - pollingStarted < (this.options.handshakeTimeoutMs || 58000); attempt++) {
      await this.request('handshake_verify', `${this.base}/mobile/${verify}`, { cookie: `addhash=${encoded}`, form: `verify=${encoded}` });
      hash = await this.cookie('t_hash_t'); if (hash) break;
      await sleep(this.options.pollMs ?? 1200);
    }
    if (!hash) throw new Error('verification_cookie_missing');
    this.session = { baseUrl: this.base, domain: new URL(this.base).hostname, addhashEncoded: encoded, tHashTEncoded: hash, fetchedAt: Date.now() };
    await writeFile(join(this.privateDir, 'session.json'), JSON.stringify(this.session, null, 2), { mode: 0o600 });
    return this.session;
  }
  auth(ott, showId = '', contentId = '') { return `addhash=${this.session.addhashEncoded}; t_hash_t=${this.session.tHashTEncoded}; lang=eng${ott !== 'nf' ? `; ott=${ott}` : ''}${showId ? `; SE${showId}=${contentId}` : ''}`; }
  async jsonCandidates(stage, endpoints, ott, params) {
    for (const endpoint of endpoints) {
      const url = new URL(endpoint, this.base); for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
      const r = await this.request(stage, url.href, { cookie: this.auth(ott) });
      if (r.kind === 'json') return JSON.parse(r.body);
    }
    throw new Error(`${stage}_unavailable`);
  }
  async search(title, year) {
    if (this.lookup.has(title)) return this.lookup.get(title);
    for (const ott of OTTS) {
      const p = prefix(ott); const endpoints = ott === 'nf' ? [`${p}/search.php`, '/search.php'] : [`${p}/search.php`];
      const parsed = await this.jsonCandidates('search', endpoints, ott, { s: title.replace(/[^a-z0-9 ]/ig, ' '), t: Math.floor(Date.now() / 1000) });
      const matches = (Array.isArray(parsed) ? parsed : parsed.status === 'n' ? [] : parsed.searchResult || []).filter(r => normalize(r.t || r.title || r.T || r.Title || '') === normalize(title));
      const match = matches.find(r => String(r.y || r.year || r.Y || r.Year) === String(year)) || matches[0];
      if (match) { const found = { id: match.id || match.Id, ott }; this.lookup.set(title, found); return found; }
    }
    throw new Error('title_not_found');
  }
  async episode(show, season, episode) {
    const key = `${show.ott}:${show.id}:${season}`; let episodes = this.episodes.get(key);
    if (!episodes) {
      const post = await this.jsonCandidates('post', paths(show.ott, 'post.php'), show.ott, { id: show.id, t: Math.floor(Date.now() / 1000) });
      episodes = (post.episodes || []).filter(r => number(r.s || r.season || r.s_num || 1) === season);
      const seasons = post.season || post.seasons || []; const target = seasons.find((s, i) => number(s.s || s.season || s.name || s.title || i + 1) === season);
      if (!episodes.some((r, i) => number(r.ep || r.episode || r.e || i + 1) === episode) && target) {
        const seasonId = target.id || target.Id || target.sid;
        if (!seasonId) throw new Error('season_id_missing');
        for (let page = 1; page <= 20; page++) {
          const data = await this.jsonCandidates('episodes', paths(show.ott, 'episodes.php'), show.ott, { s: seasonId, series: show.id, t: Math.floor(Date.now() / 1000), ...(page > 1 ? { page } : {}) });
          episodes.push(...(data.episodes || []).map((r, i) => ({ ...r, ep: number(r.ep || r.episode || r.e || (page - 1) * 10 + i + 1) })));
          if (String(data.nextPageShow) !== '1') break;
        }
      }
      this.episodes.set(key, episodes);
    }
    const match = episodes.find((r, i) => number(r.ep || r.episode || r.e || i + 1) === episode);
    if (!match) throw new Error('requested_episode_missing');
    return match.id || match.Id;
  }
  async discover(show, contentId, title) {
    if (this.routes.has(contentId)) {
      const cached = this.routes.get(contentId); const issuedAt = this.routeFetchedAt.get(contentId) || 0;
      if (Date.now() - issuedAt < TTL && (signatureExpiry(cached, issuedAt) ?? issuedAt + TTL) - Date.now() > 60000) return cached;
      this.routes.delete(contentId); this.mediaMetadata.delete(cached);
    }
    this.routeFetchedAt.set(contentId, Date.now());
    for (const endpoint of paths(show.ott, 'playlist.php')) {
      const url = new URL(endpoint, this.base); url.search = new URLSearchParams({ id: contentId, t: title, tm: Math.floor(Date.now() / 1000) });
      const r = await this.request('playlist_discovery', url.href, { cookie: this.auth(show.ott, show.id, contentId) });
      if (r.kind !== 'json') continue;
      const data = JSON.parse(r.body); const parsed = Array.isArray(data) ? data[0] : data;
      const source = parsed?.sources?.find(s => s.file && !s.file.includes('/files/220884'))?.file;
      if (source) { const discovered = new URL(source, this.base); if (discovered.searchParams.get('in')?.startsWith('unknown')) discovered.searchParams.set('in', masterToken(contentId)); this.routes.set(contentId, discovered.href); return discovered.href; }
    }
    const route = `${this.base}${prefix(show.ott)}/hls/${encodeURIComponent(contentId)}.m3u8?in=${masterToken(contentId)}&hd=off&lang=eng&hp=yes`;
    this.routes.set(contentId, route); return route;
  }
  async verifyMedia(url) {
    this.lastAttemptedMediaUrl = url;
    const audio = []; let playlist; let originalMaster; let videoInit;
    for (let depth = 0; depth < 4; depth++) {
      this.lastAttemptedMediaUrl = url;
      const cached = depth === 0 && this.options.appProfile === 'tv' ? this.mediaMetadata.get(url) : null;
      const r = cached || await this.request(depth === 0 ? 'master_manifest' : 'video_manifest', url, {
        cookie: new URL(url).origin === this.base ? this.auth(this.currentShow?.ott || 'nf', this.currentShow?.id, this.currentId) : undefined
      });
      if (r.kind !== 'hls') throw new Error('manifest_not_hls');
      if ((signatureExpiry(url) ?? Infinity) - Date.now() <= 60000) throw new Error('expired_media_signature');
      playlist = r.body; url = r.url;
      if (depth === 0 && playlist.includes('#EXT-X-STREAM-INF')) originalMaster = { body: playlist, url, kind: 'hls' };
      if (/\/files\/220884(?:[/?\s]|$)/.test(playlist)) throw new Error('rate_limited');
      const declared = playlist.split(/\r?\n/).filter(s => s.startsWith('#EXT-X-MEDIA:') && /TYPE=AUDIO/.test(s));
      if (!playlist.includes('#EXT-X-STREAM-INF')) break;
      const entries = playlistEntries(playlist, url); if (!entries.length) throw new Error('empty_master');
      const selected = entries.find(s => /720/.test(s)) || entries[0];
      const lines = playlist.split(/\r?\n/); const index = lines.findIndex(s => !s.startsWith('#') && s.trim() && new URL(s.trim(), url).href === selected);
      const group = lines[index - 1]?.match(/AUDIO="([^"]+)"/)?.[1];
      const candidates = declared.filter(s => !group || s.match(/GROUP-ID="([^"]+)"/)?.[1] === group);
      const line = candidates.find(s => s.includes('DEFAULT=YES')) || candidates.find(s => s.includes('AUTOSELECT=YES')) || candidates[0];
      const uri = line?.match(/URI="([^"]+)"/)?.[1];
      if (uri) audio.splice(0, audio.length, new URL(uri, url).href);
      url = selected;
    }
    if (!playlist.includes('#EXTINF:')) throw new Error('media_playlist_missing');
    const entries = playlistEntries(playlist, url); const encrypted = playlist.match(/#EXT-X-KEY:[^\n]*METHOD=([^,\n]+)/)?.[1];
    if (encrypted && encrypted !== 'NONE') throw new Error('encrypted_segments_require_player');
    const init = playlist.match(/#EXT-X-MAP:[^\n]*URI="([^"]+)"/)?.[1];
    if (init) { const r = await this.request('video_init', new URL(init, url).href, { binary: true }); if (r.kind !== 'iso_bmff') throw new Error('invalid_video_init'); videoInit = r.bytes; }
    let decodedSampleFrame = false;
    for (const segment of entries.slice(0, this.options.segmentCount || 2)) {
      const r = await this.request('video_segment', segment, { binary: true }); if (![200, 206].includes(r.status) || !['mpeg_ts', 'iso_bmff'].includes(r.kind)) throw new Error('invalid_video_segment');
      if (this.options.decodeSample && !decodedSampleFrame) {
        const samplePath = join(this.privateDir, 'decode-sample.bin');
        await writeFile(samplePath, videoInit ? Buffer.concat([videoInit, r.bytes]) : r.bytes, { mode: 0o600 });
        decodedSampleFrame = await decodeSample(samplePath);
      }
    }
    if (this.options.decodeSample && !decodedSampleFrame) throw new Error('sample_decoder_failed');
    if (audio.length) {
      const r = await this.request('audio_manifest', audio[0]); if (r.kind !== 'hls') throw new Error('invalid_audio_manifest');
      const audioInit = r.body.match(/#EXT-X-MAP:[^\n]*URI="([^"]+)"/)?.[1];
      if (audioInit) { const init = await this.request('audio_init', new URL(audioInit, r.url).href, { binary: true }); if (init.kind !== 'iso_bmff') throw new Error('invalid_audio_init'); }
      const segment = playlistEntries(r.body, r.url)[0]; if (!segment) throw new Error('audio_segment_missing');
      const a = await this.request('audio_segment', segment, { binary: true });
      if (![200, 206].includes(a.status) || !['mpeg_ts', 'iso_bmff'].includes(a.kind) && !((a.bytes[0] === 0xff && (a.bytes[1] & 0xf0) === 0xf0) || a.bytes.toString('ascii', 0, 3) === 'ID3')) throw new Error('invalid_audio_segment');
    }
    await writeFile(join(this.privateDir, 'last-result.json'), JSON.stringify({ url, audio, playlist }, null, 2), { mode: 0o600 });
    this.lastMediaUrl = url;
    if (originalMaster) this.mediaMetadata.set(url, originalMaster);
    return { segmentCount: Math.min(entries.length, this.options.segmentCount || 2), separateAudioValidated: audio.length > 0, evidence: 'hls_and_media_container_bytes', decodedSampleFrame, androidFirstFrameMeasured: false };
  }
  async runTitle(target, phase) {
    const start = performance.now(); const before = this.events.length; const generations = this.handshakes; let evidence; let error; let sessionReadyMs = 0;
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        try {
      await this.ensureSession(); sessionReadyMs = Math.round(performance.now() - start); const show = await this.search(target.title, target.year); const id = await this.episode(show, target.season, target.episode); this.currentShow = show; this.currentId = id; const url = await this.discover(show, id, target.title); evidence = await this.verifyMedia(url); if (this.options.appProfile !== 'mobile') this.routes.set(id, this.lastMediaUrl);
          break;
        } catch (failure) {
          if (failure.message !== 'session_rejected' || attempt === 1) throw failure;
          this.session = null; this.lookup.clear(); this.episodes.clear(); this.routes.clear(); this.mediaMetadata.clear();
        }
      }
    } catch (e) { error = safeError(e); }
    return { ...target, phase, totalMs: Math.round(performance.now() - start), handshakes: this.handshakes - generations, sessionReadyMs, success: !error, ...(error ? { error } : { ...evidence }), events: this.events.slice(before) };
  }
}
async function main() {
  const args = process.argv.slice(2); const value = key => { const i = args.indexOf(key); return i < 0 ? undefined : args[i + 1]; };
  if (args.includes('--help')) { console.log('node tools/cdn-audit/probe.mjs [--base-url https://net52.cc] [--session-file /private/session.json] [--private-dir /private/results] [--report /public/report.json] [--decode-sample]\nDefault: Mr. Robot S1E1 and Smallville S4E8, first pass + warm repeat. No credentials on command line. Tokens remain private.'); return; }
  const p = new Probe({ baseUrl: value('--base-url') || 'https://net52.cc', sessionFile: value('--session-file'), privateDir: value('--private-dir'), triggerBaseUrl: value('--trigger-base-url'), segmentCount: Number(value('--segments') || 2), decodeSample: args.includes('--decode-sample') });
  await p.init(); const targets = [{ title: 'Mr. Robot', year: 2015, season: 1, episode: 1 }, { title: 'Smallville', year: 2001, season: 4, episode: 8 }]; const runs = [];
  for (const target of targets) { const r = await p.runTitle(target, p.session ? 'warm_session_new_title' : 'cold'); runs.push(r); console.log(`${r.title} S${r.season}E${r.episode}: ${r.success ? 'segments validated' : r.error}; ${r.totalMs}ms; handshakes=${r.handshakes}`); if (['rate_limited', 'home_unavailable', 'addhash_missing', 'verification_cookie_missing', 'proxy_connect_failed'].includes(r.error)) break; }
  if (p.session) for (const target of targets) { const r = await p.runTitle(target, 'warm_repeat'); runs.push(r); console.log(`${r.title} warm repeat: ${r.success ? 'segments validated' : r.error}; ${r.totalMs}ms; handshakes=${r.handshakes}`); if (r.error === 'rate_limited') break; }
  const report = { timestamp: new Date().toISOString(), implementation: 'normal_provider_protocol_node_curl', claims: 'Container validation is not an Android decoder or first-frame measurement.', runs, skipped: targets.filter(t => !runs.some(r => r.title === t.title)).map(t => ({ ...t, reason: 'shared_handshake_unavailable' })) };
  const path = resolve(value('--report') || join(p.privateDir, 'report.json')); await writeFile(path, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 }); console.log(`Sanitized report: ${path}\nPrivate session and signed result: ${p.privateDir}`); if (runs.some(r => !r.success)) process.exitCode = 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main().catch(e => { console.error(safeError(e)); process.exitCode = 1; });
