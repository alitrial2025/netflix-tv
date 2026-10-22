import test from 'node:test';
import assert from 'node:assert/strict';
import {hotstarPremiereYear,matchesHotstarDetails,firstHotstarSeasonId} from './hotstar-identity.mjs';
import {PublicCatalogFlow} from './public-catalog-flow.mjs';
const target={title:'Perfect Crown',type:'tv',year:2026,tmdbId:'278573',season:1,episode:1};
const pilot={id:'1271531262',s:'S1',ep:'E1',date:'10 Apr 2026'};
const details={status:'y',title:'Perfect Crown',type:'t',year:'2028',season:[{s:'1',id:'1271531149'}],episodes:[pilot]};
const page='<p id="banner-content-release-year">2028</p><script>{"@type":"VideoObject","name":"Perfect Crown","uploadDate":"1970-01-01"}</script>';
const path='/tv-shows/perfect-crown/HOTSTAR_DTH_TVSHOW_1271531261';

test('dated labelled pilot establishes series premiere without trusting latest season year',()=>{
  assert.equal(hotstarPremiereYear(details),'2026');
  assert.ok(matchesHotstarDetails(details,target));
  assert.ok(matchesHotstarDetails({...details,episodes:[{...pilot,date:'2026-04-10'}]},target));
  assert.equal(matchesHotstarDetails(details,{...target,year:2028}),false);
  assert.equal(matchesHotstarDetails(details,{...target,title:'Other Show'}),false);
  assert.equal(matchesHotstarDetails(details,{...target,type:'movie'}),false);
  assert.equal(matchesHotstarDetails({...details,status:'n'},target),false);
  assert.equal(matchesHotstarDetails({...details,first_air_date:'2025-04-10'},target),false);
});

test('later episodes, contradictory pilot IDs, malformed dates and unlabelled rows cannot verify identity',()=>{
  for(const row of [{...pilot,s:'S2'},{...pilot,ep:'E2'},{...pilot,ep:'S2E1'},{...pilot,s:''},
    {...pilot,id:''},...['31 Apr 2026','2026-02-30','2026',''].map(date=>({...pilot,date}))]) {
    assert.equal(hotstarPremiereYear({...details,episodes:[row]}),null,JSON.stringify(row));
  }
  assert.equal(hotstarPremiereYear({...details,episodes:[pilot,{...pilot,id:'1271531266'}]}),null);
  assert.equal(firstHotstarSeasonId({...details,season:[{s:'1',id:'1271531149'},{s:'1',id:'1271531150'}]}),null);
  assert.equal(firstHotstarSeasonId({...details,season:[{s:'2',id:'1271531150'}]}),null);
  assert.equal(firstHotstarSeasonId(details),'1271531149');
});

function fixture(liveDetails) {
  const flow=new PublicCatalogFlow({tmdbKey:'fixture',hotstarIndex:[['tv','perfectcrown','1271531261',path]]});
  const calls=[];
  flow.request=async(stage,raw)=>{
    const u=new URL(raw); calls.push({path:u.pathname,season:u.searchParams.get('s'),series:u.searchParams.get('series')});
    if(u.pathname===path) return {body:page};
    if(u.pathname==='/mobile/hs/post.php') return {data:liveDetails};
    if(u.pathname==='/mobile/hs/episodes.php') {assert.equal(u.searchParams.get('s'),'1271531149');assert.equal(u.searchParams.get('series'),'1271531261');return {data:{episodes:[pilot]}};}
    throw Error('Unexpected request '+u.pathname);
  };
  return {flow,calls};
}

test('Perfect Crown indexed candidate verifies against live dated pilot with one metadata request',async()=>{
  const {flow,calls}=fixture(details),t={...target};
  const identity=await flow.nativeIdentity(t,false);
  assert.equal(identity.id,'1271531261');assert.equal(t.ott,'hs');
  assert.equal(await flow.episode(t,identity,{}),'1271531262');
  assert.deepEqual(calls.map(x=>x.path),[path,'/mobile/hs/post.php']);
});

test('latest-season landing post verifies through the published first-season ID',async()=>{
  const {flow,calls}=fixture({...details,episodes:[{...pilot,s:'S2',date:'10 Apr 2028'}]});
  assert.equal((await flow.nativeIdentity({...target},false)).id,'1271531261');
  assert.deepEqual(calls.map(x=>x.path),[path,'/mobile/hs/post.php','/mobile/hs/episodes.php']);
});

test('wrong premiere cannot be accepted from an untyped partner year',async()=>{
  const {flow}=fixture(details);
  assert.equal(await flow.nativeIdentity({...target,year:2028},false),null);
});
