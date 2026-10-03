const ORIGIN = 'https://www.airtelxstream.in';
export const normalizeTitle = s => String(s).normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]/g,'');
export function partnerRow(value) {
  let u; try { u = new URL(value, ORIGIN); } catch { return null; }
  if (u.origin !== ORIGIN || u.username || u.password || u.search || u.hash) return null;
  const p = u.pathname.match(/^\/(tv-shows|movies)\/((?:[a-z0-9-]|%[0-9a-f]{2})+)\/HOTSTAR_DTH_(TVSHOW|MOVIE)_([0-9]{5,20})$/i);
  if (!p || (p[1] === 'tv-shows') !== (p[3] === 'TVSHOW')) return null;
  let slug; try {slug=decodeURIComponent(p[2]);} catch {return null;}
  if(/[\/\\?#]/.test(slug)) return null;
  return [p[1] === 'tv-shows' ? 'tv' : 'movie', normalizeTitle(slug), p[4], u.pathname];
}
export function publishedLinks(html) {
  const unescaped = html.replace(/\\\//g,'/').replace(/&amp;/g,'&');
  return [...unescaped.matchAll(/(?:href=["']|["'])(https:\/\/www\.airtelxstream\.in\/[^"'\s<>]+|\/(?:movies|tv-shows|collection)(?:\/[^"'\s<>]*)?)["']/g)].map(m => m[1]);
}
export function browsePath(value) {
  let u; try { u = new URL(value, ORIGIN); } catch { return null; }
  if (u.origin !== ORIGIN || u.username || u.password || u.search || u.hash) return null;
  if (/^\/(?:movies|tv-shows)$/.test(u.pathname) || /^\/(?:movies|tv-shows|collection)\/[a-z0-9-]+$/.test(u.pathname)) return u.pathname;
  return null;
}
export function matchesPartnerPage(html, title, year, type) {
  const pageYear = html.match(/id=["']banner-content-release-year["'][^>]*>\s*(\d{4})\s*</)?.[1];
  // Series landing pages carry the latest season's local release year, not the premiere year.
  if (type !== 'tv' && pageYear !== String(year)) return false;
  return [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].some(m => {
    try { const d=JSON.parse(m[1]); return d['@type']==='VideoObject' && normalizeTitle(d.name)===normalizeTitle(title); } catch { return false; }
  });
}
