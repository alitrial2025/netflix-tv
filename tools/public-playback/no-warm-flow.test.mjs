import test from 'node:test';
import assert from 'node:assert/strict';
import {NoWarmFlow,appMasterRequest} from './no-warm-flow.mjs';

test('app mode sends AJAX headers only to PHP and app headers to provider HLS and CDN', async t => {
  const requests=[];
  t.mock.method(globalThis,'fetch',async (url,options)=>{
    requests.push({url:new URL(url),...options});
    return new Response('#EXTM3U\n#EXTINF:1,\nmedia.ts',{status:200});
  });
  const flow=new NoWarmFlow({appClientMode:true}); flow.deadline=Date.now()+5000;
  await flow.request('metadata','https://net52.cc/mobile/playlist.php?id=81458416');
  await flow.request('master','https://net52.cc/mobile/hls/81458416.m3u8');
  await flow.request('variant','https://cdn.example/video.m3u8');
  await flow.request('sample','https://cdn.example/segment.ts',true);
  assert.deepEqual(requests.map(r=>r.headers['X-Requested-With']),
    ['XMLHttpRequest','app.netmirror.netmirrornew','app.netmirror.netmirrornew','app.netmirror.netmirrornew']);
  for(const request of requests){
    assert.equal(request.credentials,'omit');
    assert.equal(request.headers.Cookie,undefined);
    assert.equal(request.headers.Authorization,undefined);
  }
  assert.equal(requests[3].headers.Range,'bytes=0-65535');
});

test('HTTP 200 dummy video is recorded independently of explicit rate-limit evidence', async t => {
  t.mock.method(globalThis,'fetch',async()=>new Response('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/files/220884/video.m3u8',{status:200}));
  const flow=new NoWarmFlow({appClientMode:true}); flow.deadline=Date.now()+5000;
  await assert.rejects(flow.request('master','https://net52.cc/mobile/hls/title.m3u8'),error=>error.code==='waiting_video_returned');
  assert.deepEqual(flow.events[0].responseSignals,{explicitRateLimit:false,waitingVideoReturned:true});
  assert.equal(flow.events[0].retryAfter,null);
});

test('HTTP 429 and Retry-After remain explicit rate-limit evidence', async t => {
  t.mock.method(globalThis,'fetch',async()=>new Response('busy',{status:429,headers:{'Retry-After':'120'}}));
  const flow=new NoWarmFlow({appClientMode:true}); flow.deadline=Date.now()+5000;
  await assert.rejects(flow.request('master','https://net52.cc/mobile/hls/title.m3u8'),error=>error.code==='rate_limited');
  assert.deepEqual(flow.events[0].responseSignals,{explicitRateLimit:true,waitingVideoReturned:false});
  assert.equal(flow.events[0].retryAfter,'120');
});

 test('guide parameters are added only to constructed provider entries',()=>{
   const built=new URL(appMasterRequest('https://net52.cc/mobile/pv/hls/id.m3u8?lang=hin','id','https://net52.cc',1700000000).url);
   assert.equal(built.searchParams.get('hd'),'off'); assert.equal(built.searchParams.get('hp'),'yes');
   assert.equal(built.searchParams.get('lang'),'hin'); assert.equal(built.searchParams.get('in').split('::')[3],'ek');
   for(const source of ['https://cdn.example/files/id/master.m3u8?in=issued::su::myes','https://net52.cc/mobile/hls/id.m3u8?in=issued::su::m'])
     assert.deepEqual(appMasterRequest(source,'id','https://net52.cc'),{url:source,constructed:false});
 });
