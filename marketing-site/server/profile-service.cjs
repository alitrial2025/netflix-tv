const { PaymentError } = require('./payment-service.cjs');
const LIMITS = Object.freeze({ plan_mobile: 1, plan_basic: 2, plan_standard: 4, plan_premium: 5 });
function fail(status, code, message) { throw new PaymentError(status, code, message); }
function profileInput(body) {
  if (!Array.isArray(body?.profiles) || !body.profiles.length || body.profiles.length > 5)
    fail(400, 'INVALID_PROFILES', 'Save between one and five profiles at a time.');
  const strings = new Set(['name','avatarUrl','avatarType','maturityRating','pin','pinHash','language','audioLanguage','subtitleLanguage','gameHandle']);
  const booleans = new Set(['isKids','isKid','autoplayNext','autoplayPreviews']);
  const numbers = new Set(['maxAge','lastUsedAt','createdAt']);
  const ids = new Set();
  return body.profiles.map(profile => {
    if (!profile || typeof profile !== 'object' || typeof profile.id !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/.test(profile.id || '') || ids.has(profile.id))
      fail(400, 'INVALID_PROFILES', 'Each profile needs a unique valid identifier.');
    ids.add(profile.id);
    const fields = {};
    for (const [key, value] of Object.entries(profile)) {
      if (key === 'id' || key === 'updatedAt') continue;
      if (strings.has(key) && typeof value === 'string' && value.length <= (key === 'avatarUrl' ? 2048 : 256) && (key !== 'name' || value.trim())) fields[key] = value;
      else if (booleans.has(key) && typeof value === 'boolean') fields[key] = value;
      else if (numbers.has(key) && Number.isSafeInteger(value) && value >= 0 && (key !== 'maxAge' || value <= 100)) fields[key] = value;
      else if (key === 'favoriteGenres' && Array.isArray(value) && value.length <= 30 && value.every(v => typeof v === 'string' && v.length <= 80)) fields[key] = value;
      else fail(400, 'INVALID_PROFILES', 'The profile contains invalid settings.');
    }
    return { id: profile.id, fields };
  });
}
async function saveProfiles(uid, body, { db, serverTimestamp, now = Date.now }) {
  const profiles = profileInput(body);
  const user = db.collection('users').doc(uid);
  const collection = user.collection('profiles');
  return db.runTransaction(async tx => {
    // Reading and writing the parent serializes concurrent creates across phones/TVs.
    const owner = await tx.get(user);
    const sub = await tx.get(user.collection('subscription').doc('current'));
    const existing = await tx.get(collection.limit(6));
    const requested = await Promise.all(profiles.map(p => tx.get(collection.doc(p.id))));
    if (requested.some((snapshot, index) => !snapshot.exists && !profiles[index].fields.name))
      fail(409, 'PROFILE_REMOVED', 'This profile no longer exists. Refresh your profile list.');
    const current = sub.exists ? sub.data() : {};
    const active = ['ACTIVE','GRACE_PERIOD'].includes(String(current.status).toUpperCase()) &&
      Number.isSafeInteger(current.expiresAt) && current.expiresAt > 0 && current.expiresAt <= 8640000000000000 &&
      now() < current.expiresAt + 172800000;
    const limit = active && Object.hasOwn(LIMITS, current.planId) ? LIMITS[current.planId] : 1;
    const newCount = requested.filter(snapshot => !snapshot.exists).length;
    // Downgrades retain existing profiles; only additional creates are restricted.
    if (newCount && existing.size + newCount > limit)
      fail(409, 'PROFILE_LIMIT', `Your plan allows ${limit} profile${limit === 1 ? '' : 's'}. Use an existing profile or upgrade your plan.`);
    for (const profile of profiles)
      tx.set(collection.doc(profile.id), { ...profile.fields, id: profile.id, updatedAt: serverTimestamp() }, { merge: true });
    if (newCount) tx.set(user, { ...(!owner.exists ? {subscriptionPlanId: 'plan_guest', subscriptionStatus: 'NONE'} : {}), profileRevision: serverTimestamp() }, { merge: true });
    const activeId = body.activeProfileId;
    if (activeId != null) {
      if (typeof activeId !== 'string' || !profiles.some(p => p.id === activeId))
        fail(400, 'INVALID_PROFILES', 'The active profile must be included in this save.');
      tx.set(user, { activeProfileId: activeId, lastSync: serverTimestamp() }, { merge: true });
    }
    return { saved: profiles.map(p => p.id), limit };
  });
}
function createProfileHandler(deps) {
  return async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return res.status(405).json({ code: 'METHOD_NOT_ALLOWED' }); }
    try {
      const token = /^Bearer ([^\s]+)$/.exec(req.headers.authorization || '')?.[1];
      if (!token) fail(401, 'AUTH_REQUIRED', 'Sign in before syncing profiles.');
      let account;
      try { account = await deps.verifyIdToken(token); } catch (_) { fail(401, 'AUTH_REQUIRED', 'Sign in again to sync profiles.'); }
      if (!account?.uid) fail(401, 'AUTH_REQUIRED', 'Sign in again to sync profiles.');
      let body = req.body;
      if (typeof body === 'string') { try { body = JSON.parse(body); } catch (_) { fail(400, 'INVALID_PROFILES', 'Invalid profile settings.'); } }
      return res.status(200).json(await saveProfiles(account.uid, body, deps));
    } catch (error) {
      if (error instanceof PaymentError) return res.status(error.status).json({ code: error.code, message: error.message });
      return res.status(503).json({ code: 'SYNC_UNAVAILABLE', message: 'Profiles are saved on this device. Reconnect to sync them.' });
    }
  };
}
module.exports = { profileInput, saveProfiles, createProfileHandler };
