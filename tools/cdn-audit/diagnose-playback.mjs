#!/usr/bin/env node
/**
 * ─────────────────────────────────────────────────────────────────────────
 *  DirectCDN Playback Diagnostic — Smallville S4E8
 *  Probes every step of the TV-app resolution chain to find what broke.
 *  Usage:  node tools/cdn-audit/diagnose-playback.mjs [--session-file path]
 *
 *  Stages tested:
 *   1. Domain resolution & health
 *   2. Session handshake  (addhash → userver → verify → t_hash_t)
 *   3. OTT catalog search (across nf,pv,hs,dp…)
 *   4. Season & episode resolution  (post.php → episodes.php)
 *   5. Playlist discovery  (playlist.php → sources[])
 *   6. Master-token HLS fetch  (::ek::m  vs  provider-issued mode)
 *   7. CDN token-mode probe  (::ek  ::su  ::su::myes  and provider-original)
 *   8. Full manifest → segment validation
 *
 *  Output:  tools/cdn-audit/diagnosis-report.json  (sanitized, no secrets)
 *           Private session & samples in a temp dir (path printed to stderr)
 * ─────────────────────────────────────────────────────────────────────────
 */
import { createHash } from 'node:crypto';
import { mkdir, writeFile, readFile, mkdtemp, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import http from 'node:http';
import https from 'node:https';
import { URL, URLSearchParams } from 'node:url';

// ── Constants ──────────────────────────────────────────────────────────
const UA = 'Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 /OS.Gatu v3.0';
const SEC_CH_UA = '"Not(A:Brand";v="99", "Android WebView";v="133", "Chromium";v="133"';
const FREECDN_HASH1 = '235ca31540ab8d90fcef4a00de8a247c';
const OTTS = ['nf', 'pv', 'hs', 'dp', 'hb', 'atp', 'pm', 'pc', 'hlu'];
const DOMAIN_POOL = ['net52.cc', 'netmirror.app', 'netmirror.gg', 'mobidetects.com', 'mobidetect.art'];
const TTL_MS = 10 * 3600_000 - 60_000;
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── Target ─────────────────────────────────────────────────────────────
const TARGET = {
  title: 'Smallville',
  year: 2001,
  tmdbId: '4607',
  type: 'tv',
  season: 4,
  episode: 8
};

// ── CLI ────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const flag = key => { const i = args.indexOf(key); return i >= 0 ? args[i + 1] : undefined; };

// ── HTTP helper ────────────────────────────────────────────────────────
function httpRequest(url, { method = 'GET', headers = {}, body, timeoutMs = 12000, maxBytes = 4_194_304, binary = false } = {}) {
  return new Promise((done, fail) => {
    const parsed = new URL(url);
    const mod = parsed.protocol === 'https:' ? https : http;
    const opts = {
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method,
      headers: {
        'User-Agent': UA,
        'Accept': '*/*',
        'Accept-Language': 'en-US,en;q=0.9',
        'sec-ch-ua': SEC_CH_UA,
        'sec-ch-ua-mobile': '?1',
        'sec-ch-ua-platform': '"Android"',
        ...headers
      },
      timeout: timeoutMs,
      rejectUnauthorized: true,
    };
    const req = mod.request(opts, res => {
      const chunks = [];
      let bytes = 0;
      res.on('data', chunk => {
        bytes += chunk.length;
        if (bytes <= maxBytes) chunks.push(chunk);
      });
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        done({
          status: res.statusCode,
          headers: res.headers,
          finalUrl: res.headers.location || url,
          body: binary ? buf : buf.toString('utf8'),
          bytes: buf.length,
        });
      });
    });
    req.on('error', fail);
    req.on('timeout', () => { req.destroy(); fail(new Error('timeout')); });
    if (body) req.write(body);
    req.end();
  });
}

// ── Token helpers ──────────────────────────────────────────────────────
function masterTokenFor(contentId) {
  const ts = String(Math.floor(Date.now() / 1000));
  const h2 = createHash('md5').update(ts + contentId).digest('hex');
  return `${FREECDN_HASH1}::${h2}::${ts}::ek::m`;
}

function buildToken(hash1, hash2, ts, mode, suffix = '') {
  let token = `${hash1}::${hash2}::${ts}::${mode}`;
  if (suffix) token += `::${suffix}`;
  return token;
}

function parseToken(raw) {
  const parts = raw.split('::');
  if (parts.length < 4) return null;
  return {
    hash1: parts[0],
    hash2: parts[1],
    timestamp: parts[2],
    mode: parts[3],
    suffix: parts.slice(4).join('::'),
    raw
  };
}

function extractTokenFromUrl(url) {
  try {
    const u = new URL(url);
    const inParam = u.searchParams.get('in');
    if (!inParam) return null;
    return parseToken(decodeURIComponent(inParam));
  } catch { return null; }
}

function extractTokensFromManifest(body) {
  const regex = /in=([^\s"'<>;&]+)/g;
  const tokens = [];
  let match;
  while ((match = regex.exec(body)) !== null) {
    const parsed = parseToken(decodeURIComponent(match[1]));
    if (parsed) tokens.push(parsed);
  }
  return tokens;
}

function ottPathPrefix(ott) {
  return ott === 'nf' ? '/mobile' : `/mobile/${ott}`;
}

function ottSearchPaths(ott) {
  const pfx = ottPathPrefix(ott);
  return pfx === '/mobile'
    ? ['/search.php', '/mobile/search.php']
    : [`${pfx}/search.php`, '/search.php', '/mobile/search.php'];
}

function normalizeTitle(s) {
  return s.toLowerCase()
    .replace(/^(the|a|an)\s+/i, '')
    .replace(/[^a-z0-9\s]/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function extractSetCookie(headers, name) {
  const cookies = [].concat(headers['set-cookie'] || []);
  for (const h of cookies) {
    if (h.startsWith(`${name}=`)) {
      return h.substring(name.length + 1).split(';')[0].trim();
    }
  }
  return '';
}

function classify(body, status) {
  const text = String(body);
  if (status === 429 || /\/files\/220884(?:[/?\s]|$)|rate[_ -]?limit|too many requests/i.test(text)) return 'rate_limited';
  if ([401, 403].includes(status) || /in=unknown|session expired|token expired|invalid token|login required|only valid users allowed/i.test(text)) return 'session_rejected';
  if (status < 200 || status >= 300) return `http_error_${status}`;
  if (text.trimStart().startsWith('<')) return 'html';
  if (text.trimStart().startsWith('#EXTM3U')) return 'hls';
  try { JSON.parse(text); return 'json'; } catch { return 'other'; }
}

function segmentKind(buf) {
  for (let offset = 0; offset < Math.min(188, buf.length); offset++) {
    if (offset + 376 < buf.length &&
        [offset, offset + 188, offset + 376].every(i => buf[i] === 0x47)) return 'mpeg_ts';
  }
  if (buf.length >= 12 && ['ftyp', 'styp', 'moof', 'sidx'].includes(buf.toString('ascii', 4, 8))) return 'iso_bmff';
  return 'unrecognized';
}

// ── Diagnostic context ─────────────────────────────────────────────────
class Diagnosis {
  constructor() {
    this.results = [];
    this.session = null;
    this.activeDomain = DOMAIN_POOL[0];
    this.privateDir = '';
  }

  log(stage, status, detail = {}) {
    const entry = { stage, status, timestamp: new Date().toISOString(), ...detail };
    this.results.push(entry);
    const icon = status === 'OK' ? '✅' : status === 'WARN' ? '⚠️' : '❌';
    console.log(`${icon}  [${stage}]  ${status}  ${detail.message || ''}`);
    return entry;
  }

  cookieHeader(ott = 'nf', showId = '', contentId = '') {
    if (!this.session) throw new Error('No session');
    let c = `addhash=${this.session.addhashEncoded}; t_hash_t=${this.session.tHashTEncoded}; lang=eng`;
    if (ott !== 'nf') c += `; ott=${ott}`;
    if (showId && contentId) c += `; SE${showId}=${contentId}`;
    return c;
  }

  // ─── STAGE 1: Domain resolution ───────────────────────────────────
  async testDomains() {
    console.log('\n════════ STAGE 1: Domain Health Check ════════');
    for (const domain of DOMAIN_POOL) {
      const t0 = performance.now();
      try {
        const res = await httpRequest(`https://${domain}/mobile/home?app=1`, { timeoutMs: 6000 });
        const elapsed = Math.round(performance.now() - t0);
        const hasAddhash = extractSetCookie(res.headers, 'addhash') !== '' || /addhash/i.test(res.body);
        const kind = classify(res.body, res.status);
        const status = res.status === 200 && hasAddhash ? 'OK' : 'WARN';
        this.log('domain_probe', status, {
          domain, httpStatus: res.status, hasAddhash, bodyKind: kind, elapsedMs: elapsed,
          message: `${domain} → HTTP ${res.status}, addhash=${hasAddhash}, ${elapsed}ms`
        });
        if (status === 'OK' && !this.session) this.activeDomain = domain;
      } catch (e) {
        this.log('domain_probe', 'FAIL', { domain, error: e.message, message: `${domain} → ${e.message}` });
      }
    }
  }

  // ─── STAGE 2: Session handshake ───────────────────────────────────
  async testHandshake() {
    console.log('\n════════ STAGE 2: Session Handshake ════════');
    const domain = this.activeDomain;

    // Step 1: Fetch addhash
    const t0 = performance.now();
    let homeRes;
    try {
      homeRes = await httpRequest(`https://${domain}/mobile/home?app=1`, {
        headers: {
          'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'navigate',
          'Sec-Fetch-Dest': 'document', 'Sec-Fetch-User': '?1',
          'Upgrade-Insecure-Requests': '1'
        }
      });
    } catch (e) {
      this.log('handshake_home', 'FAIL', { error: e.message, message: `Home fetch failed: ${e.message}` });
      return false;
    }

    let addhashEncoded = extractSetCookie(homeRes.headers, 'addhash');
    let addhashRaw = '';
    if (addhashEncoded) {
      addhashRaw = decodeURIComponent(addhashEncoded);
    } else {
      // Try HTML parsing
      const m = homeRes.body.match(/data-(?:hash|addhash|token)=["']([^"']+)["']/) ||
                homeRes.body.match(/(?:var|window\.)addhash\s*=\s*["']([^"']+)["']/) ||
                homeRes.body.match(/\b([A-Za-z0-9+/=]{10,}::[A-Za-z0-9+/=]{4,}::[A-Za-z0-9+/=]{4,})\b/);
      if (m) {
        addhashRaw = m[1];
        addhashEncoded = encodeURIComponent(addhashRaw).replace(/\+/g, '%20');
      }
    }

    const addhashParts = addhashRaw.split('::');
    this.log('handshake_addhash', addhashParts.length >= 3 ? 'OK' : 'FAIL', {
      domain, httpStatus: homeRes.status, addhashParts: addhashParts.length,
      elapsedMs: Math.round(performance.now() - t0),
      message: `addhash: ${addhashParts.length} parts (need ≥3), HTTP ${homeRes.status}`
    });

    if (addhashParts.length < 3) return false;

    // Extract dynamic params
    const quryMatch = homeRes.body.match(/(?:var\s+|window\.)?Qury\s*=\s*["']([^"']+)["']/) ||
                      homeRes.body.match(/\?([a-zA-Z0-9_]{3,10})=\s*\+\s*encodeURIComponent/);
    const vsiteMatch = homeRes.body.match(/(?:var\s+|window\.)?Vsite2?\s*=\s*["']([^"']+)["']/);
    const verifyMatch = homeRes.body.match(/["']\/(?:mobile\/)?(verify[0-9]*\.php)["']/);

    const quryParam = quryMatch?.[1] || 'hee5';
    const vsiteSubdomain = vsiteMatch?.[1] || 'userver';
    const verifyEndpoint = verifyMatch ? `/mobile/${verifyMatch[1]}` : '/mobile/verify2.php';

    this.log('handshake_params', 'OK', {
      quryParam, vsiteSubdomain, verifyEndpoint,
      message: `Dynamic params: Qury=${quryParam}, Vsite=${vsiteSubdomain}, verify=${verifyEndpoint}`
    });

    // Step 2: Trigger userver
    try {
      const ffr = encodeURIComponent(addhashRaw).replace(/\+/g, '%20');
      await httpRequest(`https://${vsiteSubdomain}.${domain}/?${quryParam}=${ffr}&a=y&t=${Math.random()}`, {
        headers: { 'Referer': `https://${domain}/` }, timeoutMs: 8000
      });
      this.log('handshake_trigger', 'OK', { message: `Triggered ${vsiteSubdomain}.${domain}` });
    } catch (e) {
      this.log('handshake_trigger', 'WARN', { error: e.message, message: `userver trigger failed (non-fatal): ${e.message}` });
    }

    // Step 3: Poll verify
    await sleep(1200);
    let tHashTEncoded = '';
    const pollStart = performance.now();
    for (let attempt = 1; attempt <= 40; attempt++) {
      try {
        const verifyRes = await httpRequest(`https://${domain}${verifyEndpoint}`, {
          method: 'POST',
          body: `verify=${addhashEncoded}`,
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'X-Requested-With': 'XMLHttpRequest',
            'Origin': `https://${domain}`,
            'Referer': `https://${domain}/mobile/home?app=1`,
            'Cookie': `addhash=${addhashEncoded}`
          }
        });

        tHashTEncoded = extractSetCookie(verifyRes.headers, 't_hash_t');
        if (tHashTEncoded) {
          this.log('handshake_verify', 'OK', {
            attempt, elapsedMs: Math.round(performance.now() - pollStart),
            message: `t_hash_t received on attempt ${attempt} (${Math.round(performance.now() - pollStart)}ms)`
          });
          break;
        }
      } catch (e) { /* continue polling */ }

      if (attempt < 40) await sleep(1200);
    }

    if (!tHashTEncoded) {
      this.log('handshake_verify', 'FAIL', { message: 'Failed to obtain t_hash_t after 40 attempts' });
      return false;
    }

    this.session = {
      domain, addhashRaw, addhashEncoded, tHashTEncoded, fetchedAt: Date.now(),
      baseUrl: `https://${domain}`
    };

    // Save session
    await writeFile(join(this.privateDir, 'session.json'), JSON.stringify(this.session, null, 2), { mode: 0o600 });
    return true;
  }

  // ─── STAGE 3: Search across OTT catalogs ──────────────────────────
  async testSearch() {
    console.log('\n════════ STAGE 3: OTT Catalog Search ════════');
    const domain = this.session.domain;
    const cookie = this.cookieHeader();
    const searchTitle = TARGET.title;
    let bestMatch = null;

    for (const ott of OTTS) {
      const paths = ottSearchPaths(ott);
      for (const searchPath of paths) {
        const ts = Math.floor(Date.now() / 1000);
        const url = `https://${domain}${searchPath}?s=${encodeURIComponent(searchTitle)}&t=${ts}`;
        const ottCookie = ott === 'nf' ? cookie : `${cookie}; ott=${ott}`;
        try {
          const res = await httpRequest(url, {
            headers: {
              'X-Requested-With': 'XMLHttpRequest',
              'Referer': `https://${domain}/`,
              'Cookie': ottCookie
            }
          });
          const kind = classify(res.body, res.status);
          if (kind !== 'json') {
            this.log('search', 'WARN', { ott, path: searchPath, bodyKind: kind, message: `${ott.toUpperCase()} ${searchPath}: ${kind}` });
            continue;
          }

          const parsed = JSON.parse(res.body);
          const results = Array.isArray(parsed) ? parsed : (parsed.status === 'n' ? [] : parsed.searchResult || []);

          for (const r of results) {
            const rTitle = r.t || r.title || r.T || r.Title || '';
            const rYear = r.y || r.year || r.Y || r.Year || '';
            const rId = r.id || r.Id || '';
            const normSearch = normalizeTitle(searchTitle);
            const normResult = normalizeTitle(rTitle);

            if (normResult === normSearch || normResult.includes(normSearch) || normSearch.includes(normResult)) {
              const match = { id: rId, title: rTitle, year: rYear, ott, path: searchPath };
              this.log('search_match', 'OK', {
                ...match,
                message: `${ott.toUpperCase()} found: "${rTitle}" (${rYear}) ID=${rId}`
              });
              if (!bestMatch || (String(rYear) === String(TARGET.year) && ott === 'nf')) {
                bestMatch = match;
              }
              if (String(rYear) === String(TARGET.year)) break;
            }
          }
          if (bestMatch && String(bestMatch.year) === String(TARGET.year)) break;
        } catch (e) {
          this.log('search', 'WARN', { ott, path: searchPath, error: e.message, message: `${ott.toUpperCase()} ${searchPath}: ${e.message}` });
        }
      }
      if (bestMatch) break;
    }

    if (!bestMatch) {
      this.log('search', 'FAIL', { message: `"${searchTitle}" not found across any OTT catalog` });
    }
    return bestMatch;
  }

  // ─── STAGE 4: Episode resolution ──────────────────────────────────
  async testEpisodeResolution(show) {
    console.log('\n════════ STAGE 4: Episode Resolution ════════');
    const domain = this.session.domain;
    const prefix = ottPathPrefix(show.ott);
    const ts = Math.floor(Date.now() / 1000);
    const cookie = this.cookieHeader(show.ott);

    // Step 1: post.php for season list
    const postUrls = prefix === '/mobile'
      ? [`https://${domain}/mobile/post.php?id=${show.id}&t=${ts}`, `https://${domain}/post.php?id=${show.id}&t=${ts}`]
      : [`https://${domain}${prefix}/post.php?id=${show.id}&t=${ts}`, `https://${domain}/mobile/post.php?id=${show.id}&t=${ts}`];

    let postJson = null;
    for (const postUrl of postUrls) {
      try {
        const res = await httpRequest(postUrl, {
          headers: {
            'X-Requested-With': 'XMLHttpRequest',
            'Cookie': cookie,
            'Referer': `https://${domain}/`
          }
        });
        if (classify(res.body, res.status) === 'json') {
          const j = JSON.parse(res.body);
          if (j.season || j.seasons || j.episodes) {
            postJson = j;
            this.log('post_detail', 'OK', {
              seasonCount: (j.season || j.seasons || []).length,
              message: `post.php returned ${(j.season || j.seasons || []).length} seasons`
            });
            break;
          }
        }
      } catch (e) { /* try next */ }
    }

    if (!postJson) {
      this.log('post_detail', 'FAIL', { message: 'post.php returned no season data' });
      return null;
    }

    // Step 2: Find target season
    const seasons = postJson.season || postJson.seasons || [];
    let targetSeasonObj = null;
    for (let i = 0; i < seasons.length; i++) {
      const s = seasons[i];
      const sVal = (s.s || s.season || s.name || s.title || `${i + 1}`).toString()
        .toLowerCase().replace('season', '').replace('s', '').trim();
      const sNum = parseInt(sVal) || (i + 1);
      if (sNum === TARGET.season) {
        targetSeasonObj = s;
        break;
      }
    }

    if (!targetSeasonObj) {
      this.log('season_find', 'FAIL', {
        availableSeasons: seasons.map(s => s.s || s.season || s.name).join(', '),
        message: `Season ${TARGET.season} not found. Available: ${seasons.map(s => s.s || s.season || s.name).join(', ')}`
      });
      return null;
    }

    const seasonId = targetSeasonObj.id || targetSeasonObj.Id;
    this.log('season_find', 'OK', { seasonId, message: `Season ${TARGET.season} ID: ${seasonId}` });

    // Step 3: Fetch episodes
    let episodeContentId = null;
    for (let page = 1; page <= 10; page++) {
      const pageQuery = page === 1 ? '' : `&page=${page}`;
      const epsUrls = prefix === '/mobile'
        ? [`https://${domain}/mobile/episodes.php?s=${seasonId}&series=${show.id}&t=${ts}${pageQuery}`,
           `https://${domain}/episodes.php?s=${seasonId}&series=${show.id}&t=${ts}${pageQuery}`]
        : [`https://${domain}${prefix}/episodes.php?s=${seasonId}&series=${show.id}&t=${ts}${pageQuery}`,
           `https://${domain}/mobile/episodes.php?s=${seasonId}&series=${show.id}&t=${ts}${pageQuery}`];

      for (const epsUrl of epsUrls) {
        try {
          const res = await httpRequest(epsUrl, {
            headers: {
              'X-Requested-With': 'XMLHttpRequest',
              'Cookie': cookie,
              'Referer': `https://${domain}/`
            }
          });
          if (classify(res.body, res.status) !== 'json') continue;
          const epsJson = JSON.parse(res.body);
          const episodes = epsJson.episodes || [];

          this.log('episodes_page', 'OK', {
            page, count: episodes.length, nextPageShow: epsJson.nextPageShow,
            message: `Page ${page}: ${episodes.length} episodes, nextPage=${epsJson.nextPageShow || 0}`
          });

          for (const ep of episodes) {
            const rawEp = (ep.ep || ep.episode || ep.e || '').toString();
            const epNum = parseInt(rawEp.replace(/\D/g, '')) || 0;
            if (epNum === TARGET.episode) {
              episodeContentId = ep.id || ep.Id;
              this.log('episode_match', 'OK', {
                episodeContentId, rawEp,
                message: `S${TARGET.season}E${TARGET.episode} content ID: ${episodeContentId}`
              });
              break;
            }
          }
          if (episodeContentId) break;

          if (String(epsJson.nextPageShow) !== '1') break;
        } catch (e) { /* try next */ }
      }
      if (episodeContentId) break;
    }

    if (!episodeContentId) {
      this.log('episode_resolution', 'FAIL', { message: `Episode ${TARGET.episode} not found in season ${TARGET.season}` });
    }
    return episodeContentId;
  }

  // ─── STAGE 5: Playlist discovery ──────────────────────────────────
  async testPlaylistDiscovery(show, contentId) {
    console.log('\n════════ STAGE 5: Playlist Discovery ════════');
    const domain = this.session.domain;
    const prefix = ottPathPrefix(show.ott);
    const ts = Math.floor(Date.now() / 1000);
    const cookie = this.cookieHeader(show.ott, show.id, contentId);
    const encId = encodeURIComponent(contentId);
    const q = encodeURIComponent(TARGET.title);

    const candidates = prefix === '/mobile'
      ? [`https://${domain}/mobile/playlist.php?id=${encId}&t=${q}&tm=${ts}`,
         `https://${domain}/playlist.php?id=${encId}&t=${q}&tm=${ts}`]
      : [`https://${domain}${prefix}/playlist.php?id=${encId}&t=${q}&tm=${ts}`,
         `https://${domain}/mobile/playlist.php?id=${encId}&t=${q}&tm=${ts}`];

    for (const playlistUrl of candidates) {
      try {
        const res = await httpRequest(playlistUrl, {
          headers: {
            'X-Requested-With': 'XMLHttpRequest',
            'Referer': `https://${domain}/mobile/home?app=1`,
            'Cookie': cookie
          }
        });

        const kind = classify(res.body, res.status);
        if (kind !== 'json') {
          this.log('playlist_php', 'WARN', { url: playlistUrl, bodyKind: kind, message: `${playlistUrl} → ${kind}` });
          continue;
        }

        const data = JSON.parse(res.body);
        const parsed = Array.isArray(data) ? data[0] : data;
        const sources = parsed?.sources || [];
        const tracks = parsed?.tracks || parsed?.captions || parsed?.subtitles || [];

        this.log('playlist_php', 'OK', {
          sourceCount: sources.length,
          trackCount: tracks.length,
          message: `playlist.php → ${sources.length} sources, ${tracks.length} subtitle tracks`
        });

        // Analyze each source
        const sourceAnalysis = [];
        for (const src of sources) {
          const fileUrl = src.file || src.src || '';
          if (!fileUrl) continue;

          const token = extractTokenFromUrl(fileUrl.startsWith('http') ? fileUrl : `https://${domain}${fileUrl}`);
          const analysis = {
            file: fileUrl.replace(/\?.*/, ''), // strip query for safety
            hasToken: !!token,
            tokenIsUnknown: fileUrl.includes('in=unknown'),
            isWaitingVideo: /\/files\/220884/.test(fileUrl),
          };
          if (token) {
            analysis.tokenMode = token.mode;
            analysis.tokenSuffix = token.suffix;
            analysis.tokenShape = `::${token.mode}${token.suffix ? '::' + token.suffix : ''}`;
          }
          sourceAnalysis.push(analysis);

          this.log('source_analysis', analysis.isWaitingVideo ? 'FAIL' : 'OK', {
            ...analysis,
            message: `Source: ${analysis.file} | token=${analysis.tokenIsUnknown ? 'UNKNOWN_PLACEHOLDER' : analysis.tokenMode ? analysis.tokenShape : 'none'} | waiting=${analysis.isWaitingVideo}`
          });
        }

        return { sources, tracks, sourceAnalysis };
      } catch (e) {
        this.log('playlist_php', 'WARN', { error: e.message, message: `playlist.php error: ${e.message}` });
      }
    }

    this.log('playlist_php', 'FAIL', { message: 'All playlist.php candidates failed' });
    return null;
  }

  // ─── STAGE 6: Master Token & HLS Manifest ─────────────────────────
  async testMasterTokenModes(show, contentId) {
    console.log('\n════════ STAGE 6: Master Token Mode Tests ════════');
    const domain = this.session.domain;
    const prefix = ottPathPrefix(show.ott);
    const hlsPrefix = prefix === '/mobile' ? '/mobile' : prefix;
    const cookie = this.cookieHeader(show.ott, show.id, contentId);

    // Test different master token modes against the PROVIDER
    const ts = String(Math.floor(Date.now() / 1000));
    const h2 = createHash('md5').update(ts + contentId).digest('hex');

    const tokenVariants = [
      { name: 'ek_m (current TV hardcoded)', token: `${FREECDN_HASH1}::${h2}::${ts}::ek::m` },
      { name: 'su_m (su mode with m suffix)', token: `${FREECDN_HASH1}::${h2}::${ts}::su::m` },
      { name: 'su_myes (provider current)', token: `${FREECDN_HASH1}::${h2}::${ts}::su::myes` },
      { name: 'ek (legacy no suffix)', token: `${FREECDN_HASH1}::${h2}::${ts}::ek` },
      { name: 'su (su no suffix)', token: `${FREECDN_HASH1}::${h2}::${ts}::su` },
    ];

    const results = [];
    for (const variant of tokenVariants) {
      const url = `https://${domain}${hlsPrefix}/hls/${contentId}.m3u8?in=${encodeURIComponent(variant.token)}&hd=off&lang=eng&hp=yes`;
      try {
        const res = await httpRequest(url, {
          headers: {
            'X-Requested-With': 'XMLHttpRequest',
            'Referer': `https://${domain}/`,
            'Cookie': cookie
          }
        });
        const kind = classify(res.body, res.status);
        const hasExtm3u = res.body.includes('#EXTM3U');
        const hasVariants = res.body.includes('#EXT-X-STREAM-INF');
        const isWaiting = /\/files\/220884/.test(res.body);
        const hasUnknown = res.body.includes('in=unknown');
        const isVideoIdMissing = res.body.includes('Video ID Missing');

        // Extract provider-issued tokens from the manifest
        const issuedTokens = extractTokensFromManifest(res.body);
        const cdnTokenModes = [...new Set(issuedTokens.map(t => `::${t.mode}${t.suffix ? '::' + t.suffix : ''}`))];

        const status = hasExtm3u && hasVariants && !isWaiting && !hasUnknown && !isVideoIdMissing ? 'OK' : 'FAIL';
        const result = {
          name: variant.name,
          tokenMode: variant.token.split('::').slice(3).join('::'),
          httpStatus: res.status,
          bodyKind: kind,
          hasManifest: hasExtm3u,
          hasVariants,
          isWaitingVideo: isWaiting,
          hasUnknownToken: hasUnknown,
          isVideoIdMissing,
          providerIssuedModes: cdnTokenModes,
          bodyBytes: res.bytes,
          status
        };

        results.push(result);
        this.log('master_token_test', status, {
          ...result,
          message: `${variant.name}: HTTP ${res.status} | manifest=${hasExtm3u} | variants=${hasVariants} | waiting=${isWaiting} | CDN modes=${cdnTokenModes.join(',') || 'none'} | ${res.bytes}B`
        });

        // If this variant works, extract the CDN URLs for stage 7
        if (status === 'OK') {
          result.cdnUrls = [];
          const lines = res.body.split('\n').map(l => l.trim());
          for (const line of lines) {
            if (line.startsWith('https://') && line.includes('in=')) {
              result.cdnUrls.push(line);
            }
            // Also check #EXT-X-MEDIA URIs
            const uriMatch = line.match(/URI="([^"]+)"/);
            if (uriMatch && uriMatch[1].startsWith('https://')) {
              result.cdnUrls.push(uriMatch[1]);
            }
          }
        }

        // Rate limit protection
        if (kind === 'rate_limited') {
          this.log('rate_limit', 'FAIL', { message: 'Rate limited! Stopping master token tests.' });
          break;
        }

        await sleep(500); // Pace requests
      } catch (e) {
        results.push({ name: variant.name, error: e.message, status: 'FAIL' });
        this.log('master_token_test', 'FAIL', { name: variant.name, error: e.message, message: `${variant.name}: ${e.message}` });
      }
    }

    return results;
  }

  // ─── STAGE 7: CDN Token Mode Probe ────────────────────────────────
  async testCdnTokenModes(providerCdnUrl) {
    console.log('\n════════ STAGE 7: CDN Token Mode Tests ════════');
    if (!providerCdnUrl) {
      this.log('cdn_token_probe', 'SKIP', { message: 'No provider CDN URL available to test' });
      return [];
    }

    const originalToken = extractTokenFromUrl(providerCdnUrl);
    if (!originalToken) {
      this.log('cdn_token_probe', 'FAIL', { message: 'Cannot parse token from CDN URL' });
      return [];
    }

    const parsed = new URL(providerCdnUrl);
    const host = parsed.host;

    // Test different token modes at the CDN level
    const variants = [
      { name: 'provider_original', token: originalToken.raw },
      { name: 'su_myes (reconstruct)', token: buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, 'su', 'myes') },
      { name: 'su_only', token: buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, 'su') },
      { name: 'ek_myes', token: buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, 'ek', 'myes') },
      { name: 'ek_only', token: buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, 'ek') },
      { name: 'ek_m (TV master formula)', token: buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, 'ek', 'm') },
      { name: 'sumyes (no separator)', token: buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, 'sumyes') },
    ];

    const results = [];
    for (const variant of variants) {
      const testUrl = new URL(providerCdnUrl);
      testUrl.searchParams.set('in', variant.token);

      try {
        const res = await httpRequest(testUrl.href, {
          headers: {
            'Origin': `https://${this.activeDomain}`,
            'Referer': `https://${this.activeDomain}/`,
            'X-Requested-With': 'app.netmirror.netmirrornew'
          }
        });

        const kind = classify(res.body, res.status);
        const isHls = res.body.includes('#EXTM3U');
        const isRejected = kind === 'session_rejected' || kind === 'cdn_route_rejected' ||
                           (res.status === 200 && res.bytes < 100 && !isHls);

        const status = isHls ? 'OK' : 'FAIL';
        const result = {
          name: variant.name,
          tokenShape: variant.token.split('::').slice(3).join('::'),
          httpStatus: res.status,
          bodyKind: kind,
          isHls,
          isRejected,
          bodyBytes: res.bytes,
          status
        };
        results.push(result);

        this.log('cdn_token_probe', status, {
          ...result,
          message: `CDN ${variant.name} (::${result.tokenShape}): HTTP ${res.status} | HLS=${isHls} | rejected=${isRejected} | ${res.bytes}B`
        });

        if (kind === 'rate_limited') {
          this.log('rate_limit', 'FAIL', { message: 'Rate limited at CDN! Stopping.' });
          break;
        }

        await sleep(400);
      } catch (e) {
        results.push({ name: variant.name, error: e.message, status: 'FAIL' });
        this.log('cdn_token_probe', 'FAIL', { name: variant.name, error: e.message, message: `CDN ${variant.name}: ${e.message}` });
      }
    }

    return results;
  }

  // ─── STAGE 8: Full manifest → segment validation ──────────────────
  async testFullPlayback(cdnUrl) {
    console.log('\n════════ STAGE 8: Full Playback Validation ════════');
    if (!cdnUrl) {
      this.log('playback', 'SKIP', { message: 'No valid CDN URL to test playback' });
      return null;
    }

    try {
      // Fetch the video variant manifest
      const res = await httpRequest(cdnUrl, {
        headers: {
          'Origin': `https://${this.activeDomain}`,
          'Referer': `https://${this.activeDomain}/`,
          'X-Requested-With': 'app.netmirror.netmirrornew'
        }
      });

      const kind = classify(res.body, res.status);
      if (kind !== 'hls') {
        this.log('video_manifest', 'FAIL', { bodyKind: kind, message: `Video manifest is ${kind}, not HLS` });
        return null;
      }

      // If this is a master manifest, drill into the variant
      let manifestBody = res.body;
      let manifestUrl = cdnUrl;

      if (manifestBody.includes('#EXT-X-STREAM-INF')) {
        // Find 720p variant or first
        const lines = manifestBody.split('\n').map(l => l.trim());
        let variantUrl = null;
        for (let i = 0; i < lines.length; i++) {
          if (lines[i].startsWith('#EXT-X-STREAM-INF')) {
            const next = lines[i + 1];
            if (next && !next.startsWith('#')) {
              const full = new URL(next, cdnUrl).href;
              if (!variantUrl || next.includes('720p')) variantUrl = full;
            }
          }
        }

        // Also check for audio
        const audioLines = lines.filter(l => l.includes('TYPE=AUDIO'));
        this.log('master_manifest', 'OK', {
          variantCount: lines.filter(l => l.startsWith('#EXT-X-STREAM-INF')).length,
          hasAudio: audioLines.length > 0,
          message: `Master: ${lines.filter(l => l.startsWith('#EXT-X-STREAM-INF')).length} variants, ${audioLines.length} audio tracks`
        });

        if (variantUrl) {
          const varRes = await httpRequest(variantUrl, {
            headers: {
              'Origin': `https://${this.activeDomain}`,
              'Referer': `https://${this.activeDomain}/`,
              'X-Requested-With': 'app.netmirror.netmirrornew'
            }
          });
          manifestBody = varRes.body;
          manifestUrl = variantUrl;
        }
      }

      // Validate segments
      const segmentLines = manifestBody.split('\n').map(l => l.trim())
        .filter(l => l && !l.startsWith('#'));

      this.log('media_manifest', 'OK', {
        segmentCount: segmentLines.length,
        hasEndList: manifestBody.includes('#EXT-X-ENDLIST'),
        message: `Media manifest: ${segmentLines.length} segments, endlist=${manifestBody.includes('#EXT-X-ENDLIST')}`
      });

      // Test first 2 segments
      let validSegments = 0;
      for (const seg of segmentLines.slice(0, 2)) {
        const segUrl = new URL(seg, manifestUrl).href;
        try {
          const segRes = await httpRequest(segUrl, {
            binary: true, maxBytes: 262144,
            headers: {
              'Origin': `https://${this.activeDomain}`,
              'Referer': `https://${this.activeDomain}/`,
              'Range': 'bytes=0-262143'
            }
          });

          const kind = segmentKind(segRes.body);
          const status = ['mpeg_ts', 'iso_bmff'].includes(kind) ? 'OK' : 'FAIL';
          validSegments += status === 'OK' ? 1 : 0;

          this.log('segment_probe', status, {
            segmentKind: kind, bytes: segRes.body.length, httpStatus: segRes.status,
            message: `Segment: ${kind} | ${segRes.body.length}B | HTTP ${segRes.status}`
          });
        } catch (e) {
          this.log('segment_probe', 'FAIL', { error: e.message, message: `Segment fetch failed: ${e.message}` });
        }
      }

      return { segmentCount: segmentLines.length, validSegments };
    } catch (e) {
      this.log('playback', 'FAIL', { error: e.message, message: `Playback test failed: ${e.message}` });
      return null;
    }
  }

  // ─── Run all diagnostics ──────────────────────────────────────────
  async run() {
    console.log('╔══════════════════════════════════════════════════════════════╗');
    console.log('║  DirectCDN Playback Diagnostic — Smallville S4E8           ║');
    console.log('╚══════════════════════════════════════════════════════════════╝');
    console.log(`Target: ${TARGET.title} S${TARGET.season}E${TARGET.episode} (${TARGET.year})`);
    console.log(`Time: ${new Date().toISOString()}\n`);

    this.privateDir = await mkdtemp(join(tmpdir(), 'cdn-diagnosis-'));
    await chmod(this.privateDir, 0o700);
    console.error(`Private output: ${this.privateDir}`);

    // Try loading existing session
    const sessionFile = flag('--session-file');
    if (sessionFile) {
      try {
        const saved = JSON.parse(await readFile(sessionFile, 'utf8'));
        if (saved.addhashEncoded && saved.tHashTEncoded && Date.now() - saved.fetchedAt < TTL_MS) {
          this.session = saved;
          this.activeDomain = saved.domain;
          this.log('session_restore', 'OK', { domain: saved.domain, message: `Restored session for ${saved.domain}` });
        }
      } catch { /* will generate new session */ }
    }

    // Stage 1
    await this.testDomains();

    // Stage 2
    if (!this.session) {
      const ok = await this.testHandshake();
      if (!ok) {
        this.log('ABORT', 'FAIL', { message: 'Cannot proceed without a valid session' });
        return this.generateReport();
      }
    }

    // Stage 3
    const show = await this.testSearch();
    if (!show) {
      this.log('ABORT', 'FAIL', { message: 'Cannot proceed — show not found in catalog' });
      return this.generateReport();
    }

    // Stage 4
    const contentId = await this.testEpisodeResolution(show);
    if (!contentId) {
      this.log('ABORT', 'FAIL', { message: 'Cannot proceed — episode not resolved' });
      return this.generateReport();
    }

    // Stage 5
    const playlist = await this.testPlaylistDiscovery(show, contentId);

    // Stage 6
    const masterResults = await this.testMasterTokenModes(show, contentId);

    // Find a working master token result with CDN URLs
    const workingMaster = masterResults.find(r => r.status === 'OK' && r.cdnUrls?.length > 0);
    const firstCdnUrl = workingMaster?.cdnUrls?.[0] || null;

    // If no working master token, check if playlist had a direct CDN URL
    let cdnUrlForTest = firstCdnUrl;
    if (!cdnUrlForTest && playlist?.sources) {
      for (const src of playlist.sources) {
        const file = src.file || '';
        if (file.startsWith('https://') && !file.includes('220884') && !file.includes('in=unknown')) {
          cdnUrlForTest = file;
          break;
        }
      }
    }

    // Stage 7
    const cdnResults = await this.testCdnTokenModes(cdnUrlForTest);

    // Stage 8
    const workingCdn = cdnResults.find(r => r.status === 'OK');
    if (workingCdn && cdnUrlForTest) {
      // Reconstruct URL with the working token mode
      const testUrl = new URL(cdnUrlForTest);
      // Use the variant that worked
      const originalToken = extractTokenFromUrl(cdnUrlForTest);
      if (originalToken) {
        const mode = workingCdn.tokenShape.split('::')[0];
        const suffix = workingCdn.tokenShape.split('::').slice(1).join('::');
        testUrl.searchParams.set('in', buildToken(originalToken.hash1, originalToken.hash2, originalToken.timestamp, mode, suffix));
        await this.testFullPlayback(testUrl.href);
      }
    } else if (cdnUrlForTest) {
      await this.testFullPlayback(cdnUrlForTest);
    }

    return this.generateReport();
  }

  generateReport() {
    console.log('\n╔══════════════════════════════════════════════════════════════╗');
    console.log('║  DIAGNOSIS SUMMARY                                          ║');
    console.log('╚══════════════════════════════════════════════════════════════╝');

    const fails = this.results.filter(r => r.status === 'FAIL');
    const oks = this.results.filter(r => r.status === 'OK');
    const warns = this.results.filter(r => r.status === 'WARN');

    console.log(`  ✅ Passed: ${oks.length}`);
    console.log(`  ⚠️  Warnings: ${warns.length}`);
    console.log(`  ❌ Failed: ${fails.length}`);

    if (fails.length > 0) {
      console.log('\n  Failures:');
      for (const f of fails) {
        console.log(`    ❌ ${f.stage}: ${f.message || f.error || 'unknown'}`);
      }
    }

    // Key finding: which token modes work at CDN vs provider?
    const masterTests = this.results.filter(r => r.stage === 'master_token_test');
    const cdnTests = this.results.filter(r => r.stage === 'cdn_token_probe');

    if (masterTests.length > 0) {
      console.log('\n  Master Token (Provider) acceptance:');
      for (const t of masterTests) {
        console.log(`    ${t.status === 'OK' ? '✅' : '❌'} ${t.name}: ${t.hasManifest ? 'manifest' : 'no manifest'} | modes=${(t.providerIssuedModes || []).join(',')}`);
      }
    }

    if (cdnTests.length > 0) {
      console.log('\n  CDN Token acceptance:');
      for (const t of cdnTests) {
        console.log(`    ${t.status === 'OK' ? '✅' : '❌'} ::${t.tokenShape}: HTTP ${t.httpStatus} | HLS=${t.isHls} | ${t.bodyBytes}B`);
      }
    }

    const report = {
      timestamp: new Date().toISOString(),
      target: TARGET,
      summary: {
        totalTests: this.results.length,
        passed: oks.length,
        warnings: warns.length,
        failed: fails.length,
        failedStages: fails.map(f => f.stage),
      },
      masterTokenAcceptance: masterTests.map(t => ({
        mode: t.name, accepted: t.status === 'OK',
        providerIssuedModes: t.providerIssuedModes || [],
      })),
      cdnTokenAcceptance: cdnTests.map(t => ({
        mode: t.name, shape: t.tokenShape, accepted: t.status === 'OK',
        httpStatus: t.httpStatus, bytes: t.bodyBytes,
      })),
      detailedResults: this.results,
    };

    return report;
  }
}

// ── Main ───────────────────────────────────────────────────────────────
const diagnosis = new Diagnosis();
const report = await diagnosis.run();

const reportPath = resolve(flag('--report') || 'tools/cdn-audit/diagnosis-report.json');
await mkdir(resolve(reportPath, '..'), { recursive: true });
await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
console.log(`\nReport saved: ${reportPath}`);
console.log(`Private data: ${diagnosis.privateDir}`);

if (report.summary.failed > 0) process.exitCode = 2;
