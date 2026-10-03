// Incremental joins of published Netflix Movie metadata, never playback availability.
export const NETFLIX_SITEMAP = 'https://www.netflix.com/sitemap/index';
const DAY = 86_400_000;
const ID = /^[0-9]{5,20}$/;
export const normalizeTitle = value => String(value ?? '').normalize('NFKD')
  .replace(/\p{M}/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
const sameTitle = (a,b) => normalizeTitle(a) !== '' && normalizeTitle(a) === normalizeTitle(b);

/** Accept the advertised, complete sitemap-index subset; reject truncated XML and foreign links. */
export function parseNetflixSitemap(xml, {minimum = 1000, maximum = 100000} = {}) {
  if (typeof xml !== 'string' || Buffer.byteLength(xml) > 12 * 1024 * 1024 || /<!DOCTYPE|<!ENTITY|<!\[CDATA\[/i.test(xml))
    throw Error('Invalid Netflix sitemap');
  const root = xml.replace(/^\uFEFF/, '').trim().match(/^(?:<\?xml\s+version=(?:"1\.0"|'1\.0')(?:\s+encoding=(?:"UTF-8"|'UTF-8'))?(?:\s+standalone=(?:"(?:yes|no)"|'(?:yes|no)'))?\s*\?>\s*)?<sitemapindex\b([^>]*)>([\s\S]*)<\/sitemapindex>$/);
  if (!root || !/\bxmlns=["']http:\/\/www\.sitemaps\.org\/schemas\/sitemap\/0\.9["']/.test(root[1]))
    throw Error('Incomplete Netflix sitemap');
  if (root[1].includes('&') || !/^(?:\s+[A-Za-z_:][\w:.-]*\s*=\s*(?:"[^"<]*"|'[^'<]*'))*\s*$/.test(root[1])) throw Error('Invalid Netflix sitemap attributes');
  const attributes = [...root[1].matchAll(/([A-Za-z_:][\w:.-]*)\s*=/g)].map(match => match[1]);
  if (new Set(attributes).size !== attributes.length) throw Error('Duplicate Netflix sitemap attributes');
  const item = /\s*<sitemap>\s*<loc>([^<]+)<\/loc>\s*(?:<lastmod>[0-9T:.+Z-]+<\/lastmod>\s*)?<\/sitemap>/gy;
  const ids = new Set(); let offset = 0, match;
  while ((match = item.exec(root[2]))) {
    if (match.index !== offset) throw Error('Invalid Netflix sitemap structure');
    offset = item.lastIndex;
    let url; try { url = new URL(match[1]); } catch { throw Error('Invalid Netflix sitemap URL'); }
    const id = url.pathname.match(/^\/sitemap\/title\/([0-9]{5,20})$/)?.[1];
    if (url.origin !== 'https://www.netflix.com' || url.username || url.password || url.search || url.hash)
      throw Error('Unexpected Netflix sitemap URL');
    if (!id) {
      // The advertised index also contains games, collections and main-site maps.
      // Validate their published path families, but do not fetch or treat them as title IDs.
      const knownOtherMap = /^\/sitemap\/(?:games\/[0-9]{5,20}|collection\/[0-9]{1,20}|main(?:\/(?:signup_regform|signup_planform|gift-cards|signup_registration|redeem|ads-plan|login|signup))?)$/.test(url.pathname);
      if (!knownOtherMap) throw Error('Unexpected Netflix sitemap URL');
      continue;
    }
    ids.add(id);
    if (ids.size > maximum) throw Error('Netflix sitemap budget exceeded');
  }
  if (root[2].slice(offset).trim() || ids.size < minimum) throw Error('Incomplete Netflix sitemap');
  return [...ids];
}

function dateYear(value) {
  const date = String(value ?? '').match(/^([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?:T[0-9:.+Z-]+)?$/);
  if (!date) return null;
  const [year,month,day] = date.slice(1).map(Number), parsed = new Date(Date.UTC(year,month-1,day));
  return year >= 1900 && year <= 2200 && parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month-1 && parsed.getUTCDate() === day ? String(year) : null;
}

/** Only the exact page's top-level Movie identity; trailer/recommendation dates are never used. */
export function netflixMovieMetadata(html, netflixId) {
  if (!ID.test(netflixId)) return null;
  const objects = [];
  const add = value => {
    if (Array.isArray(value)) value.forEach(add);
    else if (value && typeof value === 'object') {
      objects.push(value);
      if (Array.isArray(value['@graph'])) value['@graph'].forEach(add);
    }
  };
  for (const match of html.matchAll(/<script\b[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)) {
    try { add(JSON.parse(match[1])); } catch { /* Other independent scripts can still be valid. */ }
  }
  const identities = objects.filter(value => {
    let url; try { url = new URL(value.url); } catch { return false; }
    return url.origin === 'https://www.netflix.com' && !url.username && !url.password && !url.search && !url.hash &&
      new RegExp(`^/(?:[a-z]{2}(?:-[a-z]{2})?/)?title/${netflixId}$`).test(url.pathname);
  });
  if (identities.some(value => [value['@type']].flat().includes('TVSeries'))) return null;
  const movies = identities.filter(value => [value['@type']].flat().includes('Movie'));
  const metadata = movies.map(value => {
    const dates = ['datePublished','dateCreated'].filter(key => value[key]).map(key => dateYear(value[key]));
    if (!dates.length || dates.includes(null) || new Set(dates).size !== 1 || typeof value.name !== 'string' ||
        !normalizeTitle(value.name) || value.name.length > 256) return null;
    return {title:value.name, year:dates[0], netflixId};
  });
  if (metadata.some(value => !value)) return null;
  const unique = [...new Map(metadata.map(value => [JSON.stringify(value),value])).values()];
  return unique.length === 1 ? unique[0] : null;
}

export async function uniqueTmdbMovie(movie, search, details, {maximumCandidates = 4} = {}) {
  if (!search || !Array.isArray(search.results) || search.total_pages > 1) return null;
  const candidates = [...new Map(search.results.filter(value => Number.isSafeInteger(value.id) && value.id > 0 &&
    (!value.media_type || value.media_type === 'movie') && typeof value.title === 'string' &&
    dateYear(value.release_date) === movie.year).map(value => [value.id,value])).values()];
  if (!candidates.length || candidates.length > maximumCandidates) return null;
  const matches = [];
  for (const candidate of candidates) {
    const value = await details(candidate.id);
    if (!value || value.id !== candidate.id || typeof value.title !== 'string' || dateYear(value.release_date) !== movie.year ||
        value.media_type && value.media_type !== 'movie' || typeof value.name === 'string') continue;
    const aliases = [value.title,value.original_title,...(value.alternative_titles?.titles ?? []).map(row => row.title)];
    if (aliases.some(alias => sameTitle(alias,movie.title))) matches.push(String(value.id));
  }
  return matches.length === 1 ? ['movie',matches[0],'nf',movie.netflixId,''] : null;
}

function validRow(row) {
  return Array.isArray(row) && row.length === 5 && row[0] === 'movie' && /^[1-9][0-9]*$/.test(row[1]) && row[2] === 'nf' && ID.test(row[3]) && row[4] === '';
}
export function initialNetflixState() {
  return {schemaVersion:1,seenIds:[],pending:{},rows:[],lastCompleteIndexAt:null};
}
function stateCopy(previous) {
  if (!previous) return initialNetflixState();
  if (previous.schemaVersion !== 1 || !Array.isArray(previous.seenIds) || previous.seenIds.length > 100000 ||
      previous.seenIds.some(id => !ID.test(id)) || !Array.isArray(previous.rows) || previous.rows.length > 50000 ||
      previous.rows.some(row => !validRow(row)) || !previous.pending || typeof previous.pending !== 'object' || Array.isArray(previous.pending) ||
      Object.keys(previous.pending).length > 100000 || Object.entries(previous.pending).some(([id,row]) =>
        !ID.test(id) || !Number.isInteger(row?.attempts) || row.attempts < 0 || row.attempts > 4 || !Number.isFinite(row?.retryAt) || row.retryAt < 0))
    throw Error('Invalid previous Netflix metadata state');
  return structuredClone(previous);
}

/** Optional enrichment failure preserves the complete previous state and accepted joins. */
export async function enrichNetflixCatalog(native, previous, {
  transport, apiKey, now = Date.now(), maximumTitles = 24, maximumRequests = 145,
  minimumSitemapEntries = 1000, concurrency = 3, deadlineMs = 150000,
} = {}) {
  const state = stateCopy(previous);
  if (!native || native.schemaVersion !== 1 || !Array.isArray(native.rows)) throw Error('Invalid native catalog');
  const merge = current => ({...native, license:undefined, additionalSources:[NETFLIX_SITEMAP,'https://api.themoviedb.org/3/'],
    scope:'Public metadata identity candidates; Netflix movie joins require exact title/type/year. Verify live provider availability before playback.',
    rows:[...new Map([...native.rows,...current.rows].map(row => [JSON.stringify(row.slice(0,4)),row])).values()]});
  if (!apiKey || typeof transport !== 'function') return {native:merge(state),state,stats:{status:'configuration_missing',attempted:0,added:0}};
  const signal = AbortSignal.timeout(deadlineMs); let requests = 0;
  const request = async (url, maximumBytes) => {
    if (signal.aborted || requests >= maximumRequests) throw Error('Netflix enrichment request budget exceeded');
    requests++;
    return transport(url,{signal,maximumBytes});
  };
  let ids;
  try {
    ids = parseNetflixSitemap(await request(NETFLIX_SITEMAP,12*1024*1024),{minimum:minimumSitemapEntries});
    if (state.seenIds.length && ids.length < state.seenIds.length * .9) throw Error('Shrinking Netflix sitemap');
  } catch {
    return {native:merge(state),state,stats:{status:'sitemap_retained',attempted:0,added:0,requests}};
  }
  const known = new Set([...native.rows,...state.rows].filter(row => row[2] === 'nf').map(row => row[3]));
  const seen = new Set(state.seenIds), current = new Set(ids);
  const addedIds = ids.filter(id => !seen.has(id) && !known.has(id)).sort((a,b) => BigInt(a) > BigInt(b) ? -1 : BigInt(a) < BigInt(b) ? 1 : 0);
  for (const id of addedIds) state.pending[id] ??= {attempts:0,retryAt:0};
  for (const id of Object.keys(state.pending)) if (!current.has(id) || known.has(id)) delete state.pending[id];
  state.seenIds = ids;
  state.lastCompleteIndexAt = new Date(now).toISOString();
  const fresh = new Set(addedIds), queue = Object.keys(state.pending).filter(id => state.pending[id].retryAt <= now)
    .sort((a,b) => Number(fresh.has(b))-Number(fresh.has(a)) || state.pending[a].retryAt-state.pending[b].retryAt || (BigInt(a)>BigInt(b)?-1:1))
    .slice(0,Math.max(0,Math.min(maximumTitles,100)));
  const stats = {status:'complete',attempted:0,added:0,requests:0}; let index = 0;
  const json = async url => JSON.parse(await request(url,1024*1024));
  const tmdbUrl = (path,params = {}) => {
    const url = new URL(path,'https://api.themoviedb.org/3/');
    url.search = new URLSearchParams({...params,api_key:apiKey}); return url.href;
  };
  await Promise.all(Array.from({length:Math.max(1,Math.min(concurrency,4))},async () => {
    while (index < queue.length && !signal.aborted && requests < maximumRequests) {
      const id = queue[index++], pending = state.pending[id]; stats.attempted++;
      try {
        const movie = netflixMovieMetadata(await request(`https://www.netflix.com/title/${id}`,6*1024*1024),id);
        if (movie) {
          const search = await json(tmdbUrl('search/movie',{query:movie.title,primary_release_year:movie.year,language:'en-US',page:'1'}));
          const row = await uniqueTmdbMovie(movie,search,tmdb => json(tmdbUrl(`movie/${tmdb}`,{append_to_response:'alternative_titles',language:'en-US'})));
          if (row && state.rows.length < 50000) { state.rows.push(row); delete state.pending[id]; stats.added++; continue; }
        }
      } catch { /* Transport errors never erase previous accepted identities or log secret URLs. */ }
      pending.attempts = Math.min(4,pending.attempts+1);
      pending.retryAt = now + [DAY,3*DAY,7*DAY,30*DAY][pending.attempts-1];
    }
  }));
  stats.requests = requests;
  return {native:merge(state),state,stats};
}
