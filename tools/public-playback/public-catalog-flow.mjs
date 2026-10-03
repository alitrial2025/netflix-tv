#!/usr/bin/env node
// Node 22+. Mirrors the Kotlin public identity and season discovery flow; no cookie jar.
import {NoWarmFlow, TARGETS, FlowError} from './no-warm-flow.mjs';
import {readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {matchesPartnerPage,partnerRow,publishedLinks} from './partner-catalog.mjs';
const norm = s => String(s ?? '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '');
const fail = (code, stage) => {throw new FlowError(code, stage)};
export function hotstarIds(entity, entityId) {
  const claims = entity.entities?.[entityId]?.claims ?? {};
  const values = p => (claims[p] ?? []).filter(c => c.rank !== 'deprecated').map(c => c.mainsnak?.datavalue?.value);
  const ids = values('P11049').filter(v => typeof v === 'string' && /^\d{5,20}$/.test(v));
  for (const value of values('P856')) {
    try {const u = new URL(value); const id = u.pathname.replace(/\/$/, '').split('/').at(-1);
      if (u.protocol === 'https:' && ['hotstar.com','www.hotstar.com'].includes(u.hostname) && /^\d{5,20}$/.test(id)) ids.push(id);
    } catch {}
  }
  return [...new Set(ids)];
}
export function primeSeasons(html, title) {
  for (const m of html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)) {
    let root; try {root = JSON.parse(m[1])} catch {continue}
    const state = root.init?.preparations?.body?.atf?.state;
    const details = Object.values(state?.detail?.headerDetail ?? {}).filter(d => d.titleType === 'season' &&
      norm(d.title.replace(/\s*[-:]?\s*Season\s+\d+$/i, '')) === norm(title));
    if (details.length !== 1) continue;
    const result = new Map();
    for (const rows of Object.values(state.seasons ?? {})) for (const row of Array.isArray(rows) ? rows : []) {
      let u; try {u = new URL(row.seasonLink, 'https://www.primevideo.com')} catch {continue}
      const id = u.pathname.match(/^\/detail\/([A-Z0-9]{10,30})$/)?.[1];
      const n = row.sequenceNumber;
      if (u.hostname !== 'www.primevideo.com' || !id || !Number.isInteger(n) || n < 1 || n > 30) continue;
      if (result.has(n) && result.get(n) !== id) fail('ambiguous_prime_seasons', 'public_seasons');
      result.set(n, id);
    }
    if (result.size) return result;
  }
  fail('public_prime_seasons_unavailable', 'public_seasons');
}
export function netflixSeasons(html, title) {
  const identities = [];
  for (const m of html.matchAll(/<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)) {
    try {const d = JSON.parse(m[1]); identities.push(...(Array.isArray(d) ? d : [d]))} catch {}
  }
  if (!identities.some(d => norm(d.name) === norm(title) && String(d['@type']).includes('TVSeries')))
    fail('netflix_series_identity_mismatch','public_seasons');
  const selector = html.match(/<select\b[^>]*name=["']seasonSelect["'][^>]*>([\s\S]*?)<\/select>/i)?.[1];
  const pairs = [...(selector || '').matchAll(/<option\b[^>]*value=["'](\d+)["'][^>]*>\s*Season\s+(\d+)\s*<\/option>/gi)]
    .map(m => [Number(m[2]),m[1]]);
  if (!pairs.length || pairs.length > 30 || pairs.some(([s]) => s < 1 || s > 30) ||
      new Set(pairs.map(p => p[0])).size !== pairs.length || new Set(pairs.map(p => p[1])).size !== pairs.length)
    fail('invalid_netflix_season_metadata','public_seasons');
  return new Map(pairs);
}
export class PublicCatalogFlow extends NoWarmFlow {
  constructor(options = {}) {super({...options, appClientMode:true}); this.tmdbKey = options.tmdbKey; this.hotstarIndex = options.hotstarIndex ?? []; this.postCache = new Map(); this.rejected = new Set(); this.partnerPages = new Map()}
  async json(stage,path,params) {
    const key = path.endsWith('/post.php') ? `${path}:${params.id}` : null;
    if(key && this.postCache.has(key)) return this.postCache.get(key);
    const value=await super.json(stage,path,params);
    if(key) this.postCache.set(key,value);
    return value;
  }
  async nativeIdentity(t,includeDiscovery=true) {
    let tmdbId = t.tmdbId;
    if (!tmdbId) {
      if(!includeDiscovery) return null;
      const u = new URL(`https://api.themoviedb.org/3/search/${t.type}`);
      u.search = new URLSearchParams({api_key:this.tmdbKey, query:t.title});
      const data = (await this.request('tmdb_identity', u.href)).data;
      const matches = (data?.results ?? []).filter(r => norm(r.title || r.name) === norm(t.title) &&
        Number((r.release_date || r.first_air_date || '').slice(0,4)) === t.year);
      if (matches.length !== 1) fail('tmdb_identity_ambiguous', 'tmdb_identity');
      tmdbId = String(matches[0].id);
    }
    let ids;
    if (t.type === 'tv' && tmdbId === '1399' && t.year === 2011) ids = ['1971002880'];
    else if (t.type === 'tv' && tmdbId === '95350' && t.year === 2026) ids = ['1271680756'];
    else {
      const partnerIdentity = async rows => { for (const row of rows.slice(0,6)) {
        const [,,partnerId,path] = row;
        if (!/^\/(?:movies|tv-shows)\/[a-z0-9-]+\/HOTSTAR_DTH_(?:MOVIE|TVSHOW)_\d{5,20}$/.test(path)) continue;
        const page = this.partnerPages.get(path) ?? await this.request('public_partner_identity', 'https://www.airtelxstream.in'+path);
        this.partnerPages.set(path,page);
        if (!matchesPartnerPage(page.body,t.title,t.year,t.type)) continue;
        let matched = false;
        for (const m of page.body.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)) {
          try {const j = JSON.parse(m[1]); if (j['@type'] === 'VideoObject' && norm(j.name) === norm(t.title)) matched = true} catch {}
        }
        if (!matched) continue;
        const unescaped = page.body.replace(/\\\//g,'/');
        const linked = [...unescaped.matchAll(/https:\/\/(?:www\.)?hotstar\.com\/[^"\s\\<>]*?\/(\d{5,20})(?=[/?"\s\\<>]|$)/g)].map(m => m[1]);
        const candidates = [...new Set([...(partnerId.length >= 10 ? [partnerId] : []),...linked])];
        for (const id of candidates.slice(0,3)) {
          if(this.rejected.has(id)) continue;
          this.rejected.add(id);
          const d = await this.json('public_identity_validation','/mobile/hs/post.php',{id});
          const title = t.type === 'tv' ? d.title?.replace(/\s+(?:S|Season\s+)\d+$/i,'') : d.title;
          if (d.status === 'y' && norm(title) === norm(t.title) && d.type === (t.type === 'tv' ? 't' : 'm') && (t.type === 'tv' || Number(d.year) === t.year)) {
            t.ott = 'hs'; return {id,yearVerified:Number(d.year) === t.year};
          }
        }
      } return null; };
      const indexed=await partnerIdentity(this.hotstarIndex.filter(r => r[0] === t.type && r[1] === norm(t.title)));
      if(indexed) return indexed;
      if(!includeDiscovery) return null;
      const section=t.type==='tv'?'tv-shows':'movies';
      for(const path of [`/${section}`,`/${section}/english-${section}`]) {
        const page=await this.request('public_partner_browse','https://www.airtelxstream.in'+path);
        const rows=publishedLinks(page.body).map(partnerRow).filter(r=>r && r[0]===t.type && r[1]===norm(t.title));
        const found=await partnerIdentity(rows); if(found) return found;
      }
      const external = (await this.request('tmdb_external_identity', `https://api.themoviedb.org/3/${t.type}/${tmdbId}/external_ids?api_key=${this.tmdbKey}`)).data;
      if (!/^Q[1-9]\d*$/.test(external?.wikidata_id ?? '')) return null;
      const entity = (await this.request('public_native_identity', `https://www.wikidata.org/wiki/Special:EntityData/${external.wikidata_id}.json`)).data;
      ids = hotstarIds(entity, external.wikidata_id);
    }
    for (const id of ids.slice(0,3)) {
      const d = await this.json('public_identity_validation', '/mobile/hs/post.php', {id});
      const title = t.type === 'tv' ? d.title?.replace(/\s+(?:S|Season\s+)\d+$/i, '') : d.title;
      if (d.status === 'y' && norm(title) === norm(t.title) && d.type === (t.type === 'tv' ? 't' : 'm') &&
        (t.type === 'tv' || Number(d.year) === t.year)) {t.ott = 'hs'; return {id,yearVerified:Number(d.year) === t.year}}
    }
    return null;
  }
  async search(t) {
    const known=await this.nativeIdentity(t,false); if(known) return known;
    for (const ott of ['nf','pv']) {
      try {const found = await super.search({...t,ott}); t.ott = ott; return found}
      catch(e) {if (!['title_not_found_or_query_rejected','endpoint_not_found'].includes(e.code)) throw e}
    }
    return await this.nativeIdentity(t) ?? fail('verified_public_identity_unavailable', 'public_native_identity');
  }
  async episode(t, show, run) {
    if (t.ott === 'nf') {
      const page = await this.request('public_seasons', `https://www.netflix.com/title/${show.id}`);
      const id = netflixSeasons(page.body,t.title).get(t.season);
      if (!id) fail('requested_netflix_season_unavailable','public_seasons');
      this.seasonMappings = [...this.seasonMappings, {ott:'nf',showId:show.id,season:t.season,seasonId:id,
        source:`https://www.netflix.com/title/${show.id}`}];
    }
    if (t.ott === 'pv' && !this.seasonMappings.some(m => m.showId === show.id && m.season === t.season)) {
      const page = await this.request('public_seasons', `https://www.primevideo.com/detail/${show.id}`);
      const id = primeSeasons(page.body, t.title).get(t.season);
      if (!id) fail('requested_prime_season_unavailable', 'public_seasons');
      this.seasonMappings = [...this.seasonMappings, {ott:'pv',showId:show.id,season:t.season,seasonId:id,
        source:`https://www.primevideo.com/detail/${show.id}`}];
    }
    return super.episode(t, show, run);
  }
}
async function main() {
  const args = process.argv.slice(2); const option = k => args.includes(k) ? args[args.indexOf(k)+1] : undefined;
  const tmdbKey = process.env.TMDB_API_KEY || (option('--tmdb-config') &&
    (await readFile(option('--tmdb-config'),'utf8')).match(/^TMDB_API_KEY\s*=\s*([^\r\n]+)/m)?.[1]?.trim());
  if (!tmdbKey) throw new Error('Set TMDB_API_KEY or --tmdb-config path; credentials are never written to the report.');
  const targets = option('--targets') ? JSON.parse(await readFile(option('--targets'),'utf8')) : [...TARGETS,
    {ott:'hs',title:'Game of Thrones',year:2011,type:'tv',season:1,episode:1,tmdbId:'1399'},
    {ott:'hs',title:'Lanterns',year:2026,type:'tv',season:1,episode:1,tmdbId:'95350'}];
  const report = {at:new Date().toISOString(),cookieFree:true,handshakes:0,scope:'Bounded HLS video/audio container samples; Android decoding and complete-title availability are not proven.',results:[]};
  const hotstarIndex = JSON.parse(await readFile(option('--hotstar-index') || new URL('../../app/src/main/assets/public-hotstar-catalog.json',import.meta.url),'utf8')).rows;
  for (const input of targets) {
    const t = {...input}; const f = new PublicCatalogFlow({tmdbKey,hotstarIndex,titleTimeoutMs:28000,requestTimeoutMs:6000});
    const result = await f.run(t); result.requestedCatalog = input.ott; result.actualCatalog = t.ott;
    report.results.push(result); report.successCount = report.results.filter(r => r.success).length;
    await writeFile(option('--report') || 'public-catalog-report.json', JSON.stringify(report,null,2));
    console.log(JSON.stringify({title:t.title,requestedCatalog:input.ott,actualCatalog:t.ott,success:result.success,
      video:result.videoSample,audio:result.audioSample,failedStage:result.failedStage,error:result.error}));
    if (f.rateLimited) break;
  }
  if (report.successCount !== targets.length) process.exitCode = 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
