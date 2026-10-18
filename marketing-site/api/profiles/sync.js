const { initializeApp, cert, getApps } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { createProfileHandler } = require('../../server/profile-service.cjs');
module.exports = async function syncProfiles(req, res) {
  try {
    const account = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON || '{}');
    if (account.project_id !== 'netflixpro-cca67') throw new Error('Not configured');
    const app = getApps().find(a => a.name === 'payments') || initializeApp({ credential: cert(account), projectId: account.project_id }, 'payments');
    return await createProfileHandler({ verifyIdToken: token => getAuth(app).verifyIdToken(token, true),
      db: getFirestore(app), serverTimestamp: () => FieldValue.serverTimestamp() })(req, res);
  } catch (_) {
    res.setHeader('Cache-Control', 'no-store');
    return res.status(503).json({ code: 'SYNC_UNAVAILABLE', message: 'Profiles are saved on this device. Reconnect to sync them.' });
  }
};
