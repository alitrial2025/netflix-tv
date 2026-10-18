// Current trusted time contains no account information and grants no entitlement.
module.exports = function serverTime(req, res) {
  res.setHeader('Cache-Control', 'no-store, max-age=0');
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET');
    return res.status(405).json({ code: 'METHOD_NOT_ALLOWED' });
  }
  const nonce = req.query?.nonce;
  if (typeof nonce !== 'string' || !/^[a-f0-9-]{36}$/.test(nonce))
    return res.status(400).json({ code: 'INVALID_NONCE' });
  return res.status(200).json({ serverNow: Date.now(), nonce });
};
