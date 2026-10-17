// Read Netflix's published page data as JSON. Never evaluate page JavaScript.
const ID = /^[0-9]{5,20}$/;
function jsonText(raw) {
  let result = '';
  for (let i=0;i<raw.length;i++) {
    if(raw[i]==='\\' && raw[i+1]==='\\') { result+='\\\\';i++; }
    else if(raw[i]==='\\' && raw[i+1]==='x' && /^[0-9a-f]{2}$/i.test(raw.slice(i+2,i+4))) {result+='\\u00'+raw.slice(i+2,i+4);i+=3;}
    else result+=raw[i];
  }
  return result;
}
function nativeId(value) { return (typeof value==='string' || Number.isSafeInteger(value)) && ID.test(String(value)) ? String(value) : null; }
export function netflixPublicMetadata(html, expectedId) {
  if(typeof html!=='string' || html.length>6*1024*1024 || expectedId!==undefined && !ID.test(expectedId)) return null;
  const contexts=[];
  for(const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
    const assignment=match[1].match(/(?:window\.)?netflix\.reactContext\s*=\s*(\{[\s\S]*\})\s*;?\s*$/);
    if(!assignment) continue;
    try {contexts.push(JSON.parse(jsonText(assignment[1])));} catch {return null;}
  }
  if(contexts.length!==1) return null;
  const root=contexts[0],meta=root.models?.nmTitleGQL?.data?.metaData;
  const id=nativeId(meta?.videoId);
  if(!id || expectedId!==undefined && id!==expectedId || nativeId(meta?.topLevelVideoId)!==id || !['Movie','Show'].includes(meta?.type)) return null;
  let canonical;try{canonical=new URL(meta.nextPage)}catch{return null;}
  if(canonical.protocol!=='https:' || !['www.netflix.com','netflix.com'].includes(canonical.hostname) || canonical.port || canonical.username || canonical.password || canonical.search || canonical.hash ||
    !new RegExp(`^/(?:[a-z]{2}(?:-[a-z]{2})?/)?title/${id}/?$`,'i').test(canonical.pathname)) return null;
  const batches=root.models?.graphqlClcs?.data;
  if(!batches || typeof batches!=='object' || Array.isArray(batches) || Object.keys(batches).length>64) return null;
  const found=[];
  for(const [key,batch] of Object.entries(batches)) {
    let query;try{query=JSON.parse(key)}catch{continue;}
    const selections=query.variables?.parameters?.filter(p=>p.name==='titleId')??[];
    if(query.query!=='clcsHookV2' || selections.length!==1 || selections[0].value?.stringValue!==id) continue;
    const nodes=batch?.clcsHookV2?.result?.screen?.componentTree?.nodes;
    if(!Array.isArray(nodes) || nodes.length>64) continue;
    for(const node of nodes) {
      const videos=node?.data?.videos;if(!Array.isArray(videos) || videos.length>64) continue;
      for(const video of videos) {
        if(nativeId(video?.videoId)!==id) continue;
        if(video.__typename!==meta.type || typeof video.title!=='string' || !video.title.trim() || video.title.length>256) return null;
        const year=Number.isInteger(video.latestYear) && video.latestYear>=1900 && video.latestYear<=2200 ? String(video.latestYear) : null;
        if(meta.type==='Movie' && !year) return null;
        const seasons={};const edges=video.seasons?.edges??[];
        if(!Array.isArray(edges) || edges.length>60) return null;
        const ids=new Set();
        if(meta.type==='Show') for(const edge of edges) {
          const s=edge?.node,n=s?.numberLabelV2?.match(/^Season\s+([0-9]+)$/i)?.[1],sid=nativeId(s?.videoId);
          if(s?.__typename!=='Season' || s.showMemberType!=='SEASON' || !n || Number(n)<1 || Number(n)>60 || !sid || Object.hasOwn(seasons,Number(n)) || ids.has(sid)) return null;
          seasons[Number(n)]=sid;ids.add(sid);
        }
        found.push({id,title:video.title,type:meta.type==='Movie'?'movie':'tv',year:meta.type==='Movie'?year:null,seasons});
      }
    }
  }
  const unique=[...new Map(found.map(v=>[JSON.stringify(v),v])).values()];
  return unique.length===1?unique[0]:null;
}
