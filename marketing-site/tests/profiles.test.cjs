const test = require('node:test');
const assert = require('node:assert/strict');
const { profileInput, saveProfiles, createProfileHandler } = require('../server/profile-service.cjs');
function mockDb() {
  const data = new Map(); let queue = Promise.resolve();
  const snapshot = path => ({ exists: data.has(path), data: () => data.get(path) });
  const collection = path => ({ doc: id => doc(`${path}/${id}`), limit: limit => ({ query: path, limit }) });
  const doc = path => ({ path, collection: name => collection(`${path}/${name}`) });
  return { data, collection, runTransaction: fn => {
    const result = queue.then(async () => {
      const writes = [];
      const tx = { get: async ref => {
        assert.equal(writes.length, 0, 'Firestore requires reads before writes');
        if (ref.query) return { size: [...data.keys()].filter(path => path.startsWith(ref.query + '/') && !path.slice(ref.query.length + 1).includes('/')).slice(0, ref.limit).length };
        return snapshot(ref.path);
      }, set: (ref, value) => writes.push([ref.path, value]) };
      const value = await fn(tx);
      writes.forEach(([path, fields]) => data.set(path, { ...data.get(path), ...fields }));
      return value;
    }); queue = result.catch(() => {}); return result;
  } };
}
const now = 1800000000000;
const deps = db => ({ db, now: () => now, serverTimestamp: () => now });
const profile = id => ({ id, name: 'Viewer', isKids: false, favoriteGenres: ['Drama'] });
test('concurrent profile creates cannot exceed a Guest or paid account limit', async () => {
  for (const [plan, limit] of [['plan_guest',1],['plan_mobile',1],['plan_basic',2],['plan_standard',4],['plan_premium',5]]) {
    const db = mockDb();
    db.data.set('users/alice/subscription/current',{ planId: plan, status: 'ACTIVE', expiresAt: now + 1000 });
    const results = await Promise.allSettled(Array.from({ length: 6 }, (_, i) => saveProfiles('alice',{profiles:[profile(String(i))]},deps(db))));
    assert.equal(results.filter(r => r.status === 'fulfilled').length, limit, plan);
    assert.ok(results.filter(r => r.status === 'rejected').every(r => r.reason.code === 'PROFILE_LIMIT'));
  }
});
test('downgrade retains existing profiles and supports edits but blocks further additions', async () => {
  const db = mockDb();
  for (let i = 0; i < 5; i++) db.data.set(`users/alice/profiles/${i}`,profile(String(i)));
  db.data.set('users/alice/subscription/current',{planId:'plan_mobile',status:'ACTIVE',expiresAt:now+1000});
  await saveProfiles('alice',{profiles:[{...profile('4'),name:'Renamed'}]},deps(db));
  assert.equal(db.data.get('users/alice/profiles/4').name,'Renamed');
  await assert.rejects(saveProfiles('alice',{profiles:[profile('new')]},deps(db)),{code:'PROFILE_LIMIT'});
  assert.equal([...db.data.keys()].filter(p=>p.startsWith('users/alice/profiles/')).length,5);
});
test('expired suspended and unknown plans never gain additional profiles', async () => {
  for (const sub of [
    {planId:'plan_premium',status:'SUSPENDED',expiresAt:now+1000},
    {planId:'plan_premium',status:'ACTIVE',expiresAt:now-172800000},
    {planId:'plan_admin',status:'ACTIVE',expiresAt:now+1000}]) {
    const db=mockDb();db.data.set('users/alice/subscription/current',sub);
    await assert.rejects(saveProfiles('alice',{profiles:[profile('a'),profile('b')]},deps(db)),{code:'PROFILE_LIMIT'});
    assert.equal(db.data.size,1);
  }
});
test('profile payload cannot write arbitrary account fields or invalid settings', () => {
  for (const profiles of [[{...profile('a'),subscriptionPlanId:'plan_premium'}],[profile('../other')],[profile('a'),profile('a')],[{...profile('a'),isKids:'false'}]])
    assert.throws(()=>profileInput({profiles}),{code:'INVALID_PROFILES'});
});
test('failed profile transaction leaves the entire profile batch unchanged', async () => {
  const db=mockDb();
  await assert.rejects(saveProfiles('alice',{profiles:[profile('a')],activeProfileId:'someone_else'},deps(db)),{code:'INVALID_PROFILES'});
  assert.equal(db.data.size,0);
});
test('profile endpoint rejects unauthenticated requests before touching the database', async () => {
  let calls=0;let status;
  const handler=createProfileHandler({verifyIdToken:async()=>{calls++;throw Error('revoked');}});
  const res={setHeader(){},status:n=>{status=n;return res;},json(){}};
  await handler({method:'POST',headers:{},body:{profiles:[profile('a')]}},res);
  assert.equal(status,401);assert.equal(calls,0);
  await handler({method:'POST',headers:{authorization:'Bearer revoked'},body:{profiles:[profile('a')]}},res);
  assert.equal(status,401);assert.equal(calls,1);
});

test('profile endpoint derives ownership from the verified token and ignores a body account override', async () => {
  const db=mockDb();let status;let result;
  const handler=createProfileHandler({...deps(db),verifyIdToken:async()=>({uid:'alice'})});
  const res={setHeader(){},status:n=>{status=n;return res;},json:value=>{result=value;}};
  await handler({method:'POST',headers:{authorization:'Bearer valid'},body:{uid:'bob',profiles:[profile('a')]}},res);
  assert.equal(status,200);assert.deepEqual(result.saved,['a']);
  assert.ok(db.data.has('users/alice/profiles/a'));
  assert.ok([...db.data.keys()].every(path=>path.startsWith('users/alice')));
});

test('a queued metadata update cannot recreate a deleted nameless profile', async () => {
  const db=mockDb();
  await assert.rejects(saveProfiles('alice',{profiles:[{id:'removed',lastUsedAt:now}]},deps(db)),{code:'PROFILE_REMOVED'});
  assert.equal(db.data.size,0);
  assert.throws(()=>profileInput({profiles:[{id:'empty',name:'  '}]}),{code:'INVALID_PROFILES'});
});
