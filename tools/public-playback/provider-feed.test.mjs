import test from 'node:test';
import assert from 'node:assert/strict';
import {makeFeed} from './build-provider-feed.mjs';
test('failed or partial refreshes cannot replace the published identity feed',()=>{
 const good={schemaVersion:1,generatedAt:'2026-10-03T00:00:00.000Z',rows:Array.from({length:1000},(_,i)=>['tv',String(i+1),'hs',String(1971000000+i),'Q'+(i+1)])};
 assert.throws(()=>makeFeed(good,{schemaVersion:1,rows:[]}),/Incomplete/);
 assert.throws(()=>makeFeed({...good,schemaVersion:99},good),/Incomplete/);
 const partner={...good,rows:Array.from({length:1000},(_,i)=>['tv','title'+i,String(1971000000+i),`/tv-shows/title${i}/HOTSTAR_DTH_TVSHOW_${1971000000+i}`])};
 assert.throws(()=>makeFeed(good,good),/Invalid partner/);
 const feed=makeFeed(good,partner,123456789);
 assert.equal(feed.generatedAt,123456789);assert.equal(feed.nativeRows.length,1000);
 assert.equal(feed.schemaVersion,2);assert.equal(feed.sourceGeneratedAt.native,good.generatedAt);
});
