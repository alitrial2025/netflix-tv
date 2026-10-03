import test from 'node:test';
import assert from 'node:assert/strict';
import { makeRelease, parseBadging, parseSigners, secureUrl } from './release-lib.mjs';

const signer = 'a'.repeat(64);
const input = () => ({
  channel: 'tv', metadata: { packageName: 'com.netflixprotv.apk', versionCode: 2, versionName: '2', minSdk: 24, targetSdk: 35 },
  bytes: 1024, sha256: 'b'.repeat(64), signers: [signer], apkUrl: '/releases/tv.apk', gateEnabled: true,
  previous: { available: true, versionCode: 1, signingCertificateSha256: [signer] }
});

test('a newer release preserves package, signer and update gate metadata', () => {
  const release = makeRelease(input());
  assert.equal(release.versionCode, 2);
  assert.equal(release.supportsUpdateGate, true);
  assert.equal(release.buildType, 'release');
  assert.equal(release.mandatory, true);
  assert.deepEqual(release.signingCertificateSha256, [signer]);
});

test('replacing a debug baseline requires explicit migration and records a fresh install', () => {
  const data = { ...input(), signers: ['c'.repeat(64)], previous: { ...input().previous, buildType: 'debug' } };
  assert.throws(() => makeRelease(data), /Signing key changed/);
  const release = makeRelease({ ...data, replaceDebugBaseline: true });
  assert.equal(release.requiresFreshInstallFromDebug, true);
  assert.equal(release.mandatory, true);
});

test('debug migration cannot change a production signing key', () => {
  assert.throws(() => makeRelease({ ...input(), signers: ['c'.repeat(64)],
    previous: { ...input().previous, buildType: 'release' }, replaceDebugBaseline: true }), /Signing key changed/);
});
test('an update cannot replace the installed signing key', () => {
  assert.throws(() => makeRelease({ ...input(), signers: ['c'.repeat(64)] }), /Signing key changed/);
});
test('equal and older versions cannot be published as updates', () => {
  for (const versionCode of [1, 0]) {
    const data = input();
    assert.throws(() => makeRelease({ ...data, metadata: { ...data.metadata, versionCode } }), /Increase versionCode/);
  }
});
test('debug APK signers are rejected', () => {
  assert.throws(() => parseSigners(`Signer #1 certificate DN: CN=Android Debug\nSigner #1 certificate SHA-256 digest: ${signer}`), /release-signed/);
});
test('mobile APKs cannot be published on the TV channel', () => {
  assert.throws(() => parseBadging("package: name='com.netflixpro.apk' versionCode='2' versionName='2'\nsdkVersion:'24'\ntargetSdkVersion:'35'", 'tv'), /Wrong package/);
});
test('release URLs require HTTPS without embedded credentials', () => {
  for (const url of ['http://example.com/app.apk', 'https://user:secret@example.com/app.apk', 'https://example.com/app.apk#fragment']) {
    assert.throws(() => secureUrl(url));
  }
  assert.equal(secureUrl('https://example.com/app.apk').protocol, 'https:');
});
