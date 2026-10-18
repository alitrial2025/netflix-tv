const test = require('node:test');
const assert = require('node:assert/strict');
const handler = require('../api/time.js');
function invoke(method, nonce) {
  const result = { headers: {} };
  const res = { setHeader: (k, v) => result.headers[k] = v,
    status: n => { result.status = n; return res; }, json: body => result.body = body };
  handler({ method, query: { nonce } }, res);
  return result;
}
test('fresh time is bound to a request nonce and never cached', () => {
  const nonce = '12345678-1234-1234-1234-123456789abc';
  const start = Date.now();
  const response = invoke('GET', nonce);
  assert.equal(response.status, 200);
  assert.equal(response.body.nonce, nonce);
  assert.ok(response.body.serverNow >= start && response.body.serverNow <= Date.now());
  assert.match(response.headers['Cache-Control'], /no-store/);
  assert.equal(invoke('GET', [nonce]).status, 400);
  assert.equal(invoke('GET', '').status, 400);
  assert.equal(invoke('POST', nonce).status, 405);
});
