// Explicit TMDB/Netflix alternate ordering. See docs/black-clover-episode-order.md.
const ranges=[[1,51],[52,102],[103,154],[155,170]];
const norm=s=>String(s??'').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]/g,'');
const aliases=[[1378799, "A Young Man's Vow", "The Boys' Promise", "80238227"], [1378975, "To the Royal Capital!", "To the Royal Capital of the Clover Kingdom!", "80238228"], [1387474, "The Road to the Wizard King", "The Path to the Wizard King", "80238230"], [1390857, "Another New Member", "The Other New Recruit", "80238232"], [1392855, "Go! Go! My First Mission", "Go! Go! First Mission", "80238233"], [1401846, "Guardians", "Those Who Protect", "80238235"], [1404851, "What Happened One Day in the Castle Town", "What Happened on a Certain Day in the Castle Town", "80238236"], [1444656, "Wild Magic Dance", "Wild Magic Danc", "80238247"], [1444716, "The King of the Crimson Lions", "The Crimson Lion King", "80238248"], [1607999, "The Battlefield Decision", "Battlefield Decision", "81313238"], [1639300, "Bettering One Another", "Those Who Boost Each Other Up", "81313242"], [2173598, "Nero Reminiscences… (1)", "Nero Reminisces... Part One", "81399582"], [2179680, "Nero Reminiscences… (2)", "Nero Reminisces... Part Two", "81399583"], [2206961, "The Devil Megicula", "Devil Megicula", "81399588"], [2223054, "The New Magic Knights Captain Conference", "The New Magic Knight Captain Conference", "81399589"], [2448655, "Two Things to Look for", "Two Things We Need to Find", "81399608"]];
export function orderedProviderSeason(target,showId) {
  if(String(target.tmdbId)!=='73223' || target.type!=='tv' || target.ott!=='nf' || showId!=='80238012') return null;
  const index=ranges.findIndex(([a,b])=>target.episode>=a && target.episode<=b);
  if(index<0 || target.season!==1 && target.season!==index+1) throw Error('Requested episode belongs to a different season');
  return index+1;
}
export function orderedEpisodeMetadata(data) {
  if(data?.id!==91272 || !Array.isArray(data.episodes) || data.episodes.length!==170) throw Error('Episode ordering metadata identity mismatch');
  const result=data.episodes;
  if(result.some(e=>!Number.isSafeInteger(e.id)||e.id<1||e.season_number!==1||!Number.isInteger(e.episode_number)||e.episode_number<1||e.episode_number>170||typeof e.name!=='string'||!e.name.trim()||e.name.length>256) ||
    new Set(result.map(e=>e.id)).size!==170 || new Set(result.map(e=>e.episode_number)).size!==170) throw Error('Episode ordering metadata is ambiguous');
  return result;
}
export function joinOrderedEpisodes(rows,metadata,season) {
  if(!Array.isArray(rows)||!ranges[season-1]) throw Error('Invalid ordered episode catalog');
  const [a,b]=ranges[season-1],eligible=metadata.filter(e=>e.episode_number>=a && e.episode_number<=b),result=[];
  for(const row of rows) {
    const s=String(row.s||row.season||row.s_num||'').match(/^(?:season|s)?\s*([0-9]+)$/i)?.[1];
    if(Number(s)!==season) throw Error('Provider returned a different season');
    const id=String(row.id||row.Id||row.sid||''),title=row.t||row.title||'';
    const ep=Number(String(row.ep||row.episode||row.e||'').match(/^(?:s[0-9]+\s*)?(?:episode|ep|e)?\s*([0-9]+)$/i)?.[1]);
    if(!/^[0-9]{5,20}$/.test(id)||!Number.isInteger(ep)||ep<1||ep>500) throw Error('Invalid provider episode identity');
    const matches=eligible.filter(e=>norm(e.name)===norm(title)||aliases.some(([eid,canonical,published,nid])=>eid===e.id&&norm(canonical)===norm(e.name)&&norm(published)===norm(title)&&nid===id));
    if(matches.length>1) throw Error('Provider episode identity is ambiguous');
    if(matches.length===1) result.push({number:matches[0].episode_number,id,providerSeason:season,providerEpisode:ep});
  }
  if(new Set(result.map(e=>e.number)).size!==result.length||new Set(result.map(e=>e.id)).size!==result.length) throw Error('Provider episode identity is ambiguous');
  return result;
}
