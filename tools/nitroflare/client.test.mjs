import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { NitroFlare, ProviderError, providerURL, uploadedIDs } from './client.mjs';
const id = '276AF587369E929';
const success = result => new Response(JSON.stringify({ type: 'success', result }));
test('provider URLs reject plaintext, credentials and foreign/lookalike hosts', () => {
  for (const u of ['http://nitroflare.com/x','https://nitroflare.com.evil.test/x','https://evil.test/x','https://user:pass@nitroflare.com/x','https://nitroflare.com:444/x']) assert.throws(() => providerURL(u));
  assert.equal(providerURL('https://s42.nitroflare.com/upload').hostname,'s42.nitroflare.com');
});
test('file info batches at 100 and handles missing result maps', async () => {
  const ids = Array.from({length:205}, (_,i)=>i.toString(16).padStart(15,'0'));
  const sizes = [];
  const client = new NitroFlare({fetchImpl:async url=> {
    const group = url.searchParams.get('files').split(','); sizes.push(group.length);
    return success({files:Object.fromEntries(group.map(x=>[x,{status:'online'}]))});
  }});
  assert.equal(Object.keys(await client.info(ids)).length,205);
  assert.deepEqual(sizes,[100,100,5]);
  assert.deepEqual(await client.info([]),{});
  await assert.rejects(new NitroFlare({fetchImpl:async()=>success({})}).info([id]),/file-info/);
});
test('free handshake enforces delay and CAPTCHA and does not leak token', async () => {
  let now=1000; const requests=[];
  const client=new NitroFlare({now:()=>now,fetchImpl:async url=> {
    requests.push(url);
    return requests.length===1 ? success({linkType:'free',delay:120,accessLink:`getDownloadLink?file=${id}&hash1=secret`,recaptchaPublic:'site'}) : success({url:'https://s42.nitroflare.com/d/video.mp4',linkType:'free'});
  }});
  const pending=await client.begin(id);
  assert.equal(pending.readyAt,121000);
  await assert.rejects(client.complete(pending,'answer'),/waiting/);
  now=121000;
  await assert.rejects(client.complete(pending,''),/CAPTCHA/);
  assert.equal(requests.length,1);
  assert.equal((await client.complete(pending,'answer')).linkType,'free');
  assert.equal(requests[1].searchParams.get('captcha'),'answer');
});
test('handshake rejects wrong file and arbitrary endpoints', async () => {
  const client=new NitroFlare({fetchImpl:async()=>success({linkType:'free',delay:0,accessLink:'getDownloadLink?file=000000000000000'})});
  await assert.rejects(client.begin(id),/different file/);
  for(const link of ['https://evil.test/x','/api/v2/getKeyInfo','https://s42.nitroflare.com/api/v2/getDownloadLink']) assert.throws(()=>client.access(link));
});
test('throttling and transport failures are not retried or printed verbatim', async () => {
  let count=0;
  const client=new NitroFlare({fetchImpl:async()=>{count++;return new Response(JSON.stringify({type:'error',code:12,message:'secret'}));}});
  await assert.rejects(client.info([id]),e=>e instanceof ProviderError && e.code===12 && !e.message.includes('secret'));
  assert.equal(count,1);
  const network=new NitroFlare({fetchImpl:async()=>{throw new Error('URL?premiumKey=secret');}});
  await assert.rejects(network.info([id]),e=>!e.message.includes('secret'));
});
test('upload sends a file-backed multipart body only to validated HTTPS origin', async () => {
  const dir=await mkdtemp(join(tmpdir(),'nf-upload-'));
  try {
    const file=join(dir,'sample.mp4');await writeFile(file,'sample-data');
    const calls=[];
    const client=new NitroFlare({env:{NITROFLARE_USER_HASH:'test-only'},fetchImpl:async(url,opts)=> {
      calls.push(url);
      assert.equal(opts.redirect,'error');
      if(calls.length===1)return new Response('https://s42.nitroflare.com/upload');
      assert.equal(opts.body.get('user'),'test-only');
      assert.equal(await opts.body.get('files').text(),'sample-data');
      return new Response(JSON.stringify({url:`https://nitroflare.com/view/${id}/sample.mp4`}));
    }});
    assert.deepEqual(uploadedIDs(await client.upload(file)),[id]);
    const rejected=new NitroFlare({env:{NITROFLARE_USER_HASH:'test-only'},fetchImpl:async()=>new Response('http://s42.nitroflare.com/upload')});
    await assert.rejects(rejected.upload(file),/HTTPS/);
    await assert.rejects(new NitroFlare({env:{}}).upload(file),/USER_HASH/);
  } finally {await rm(dir,{recursive:true,force:true});}
});
test('upload ID extraction ignores non-provider links',()=> {
  assert.deepEqual(uploadedIDs({files:[{url:`https://nitroflare.com/view/${id}/x`},{url:'https://evil.test/view/000000000000000/x'}]}),[id]);
});
test('CLI registers IDs, makes empty check without network, and protects state lock',async()=> {
  const dir=await mkdtemp(join(tmpdir(),'nf-state-'));
  const env={...process.env,NITROFLARE_STATE_DIR:dir};
  const cli=new URL('./cli.mjs',import.meta.url).pathname;
  try {
    const empty=JSON.parse(execFileSync(process.execPath,[cli,'status'],{env,encoding:'utf8'}));
    assert.deepEqual(empty.files,[]);
    execFileSync(process.execPath,[cli,'register',id],{env});
    const state=JSON.parse(await readFile(join(dir,'registry.json'),'utf8'));
    assert.ok(state.files[id]);
    await writeFile(join(dir,'.lock'),'');
    const blocked=spawnSync(process.execPath,[cli,'register','000000000000000'],{env,encoding:'utf8'});
    assert.equal(blocked.status,1);assert.match(blocked.stderr,/lock/);
    assert.equal(Object.keys(JSON.parse(await readFile(join(dir,'registry.json'),'utf8')).files).length,1);
  }finally{await rm(dir,{recursive:true,force:true});}
});
