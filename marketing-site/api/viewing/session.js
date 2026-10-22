const { initializeApp, cert, getApps } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore } = require('firebase-admin/firestore');
const { createViewingHandler } = require('../../server/viewing-service.cjs');
module.exports = async function viewingSession(req,res) {
  try {
    const account=JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON||'{}');
    if(account.project_id!=='netflixpro-cca67') throw Error('Not configured');
    const app=getApps().find(a=>a.name==='payments')||initializeApp({credential:cert(account),projectId:account.project_id},'payments');
    return await createViewingHandler({db:getFirestore(app),verifyIdToken:token=>getAuth(app).verifyIdToken(token,true)})(req,res);
  } catch(_) {res.setHeader('Cache-Control','no-store');return res.status(503).json({code:'VIEWING_UNAVAILABLE',message:'Watch together is temporarily unavailable. Try again shortly.'});}
};
