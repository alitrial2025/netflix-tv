// Provider metadata evidence only. A generic year can describe the latest season.
import {normalizeTitle} from './partner-catalog.mjs';

const field = (row, first, second) => String(row?.[first] || row?.[second] || '').trim();
const nativeId = value => /^[0-9]{5,20}$/.test(String(value ?? ''));
function premiereDate(raw) {
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  let year, month, day;
  const iso = String(raw ?? '').trim().match(/^(\d{4})-(\d{2})-(\d{2})$/);
  const named = String(raw ?? '').trim().match(/^(\d{1,2}) ([A-Z][a-z]{2}) (\d{4})$/);
  if (iso) [, year, month, day] = iso.map(Number);
  else if (named) { year=Number(named[3]); month=months.indexOf(named[2])+1; day=Number(named[1]); }
  else return null;
  if (year < 1900 || year > 2100 || month < 1 || month > 12) return null;
  const date = new Date(Date.UTC(year,month-1,day));
  return date.getUTCFullYear()===year && date.getUTCMonth()===month-1 && date.getUTCDate()===day
    ? `${year}-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}` : null;
}
export function hotstarPremiereYear(details) {
  for (const name of ['first_air_date','firstAirDate','seriesFirstAirDate']) {
    const year=String(details?.[name] ?? '').slice(0,4);
    if (/^[0-9]{4}$/.test(year)) return year;
  }
  if (details?.status!=='y' || details.type!=='t' || !Array.isArray(details.episodes) || details.episodes.length>1000) return null;
  const pilots=details.episodes.filter(row => /^(?:s|season)?\s*0*1$/i.test(field(row,'s','season')) &&
    /^(?:s0*1\s*)?(?:e|ep|episode)?\s*0*1$/i.test(field(row,'ep','episode')));
  if (!pilots.length) return null;
  const identities=new Set();
  for (const row of pilots) {
    const date=premiereDate(row.date);
    if (!nativeId(row.id) || !date) return null;
    identities.add(`${row.id}:${date}`);
  }
  return identities.size===1 ? [...identities][0].split(':')[1].slice(0,4) : null;
}
export function matchesHotstarDetails(details, target, typedMapping=false) {
  const title=target.type==='tv' ? String(details?.title ?? '').replace(/\s+(?:S|Season\s+)\d+$/i,'') : details?.title;
  return details?.status==='y' && normalizeTitle(title)===normalizeTitle(target.title) &&
    details.type===(target.type==='tv' ? 't' : 'm') &&
    (target.type==='tv' ? typedMapping || hotstarPremiereYear(details)===String(target.year) : String(details.year)===String(target.year));
}
export function firstHotstarSeasonId(details) {
  const rows=details?.season ?? details?.seasons ?? [];
  if (!Array.isArray(rows)) return null;
  const ids=[...new Set(rows.filter(row=>/^(?:s|season)?\s*0*1$/i.test(String(row?.s || row?.season || row?.name || row?.title || '').trim()))
    .map(row=>String(row.id ?? row.Id ?? row.sid ?? '')))];
  return ids.length===1 && nativeId(ids[0]) ? ids[0] : null;
}
