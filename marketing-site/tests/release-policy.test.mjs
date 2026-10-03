import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { readRelease } from '../assets/release-policy.mjs';

const origin = 'https://npro-app.vercel.app';
const current = channel => JSON.parse(readFileSync(new URL(`../updates/${channel}.json`, import.meta.url)));

test('existing project APKs retain correct channel, build label and fingerprint', () => {
  for (const channel of ['tv', 'mobile']) {
    const release = readRelease(current(channel), channel, origin);
    assert.ok(['debug', 'release'].includes(release.buildType));
    assert.ok(release.apkUrl.startsWith(origin + '/downloads/') || release.apkUrl.startsWith('https://github.com/alitrial2025/'));
    assert.equal(release.signingCertificateSha256.length, 1);
  }
});
test('metadata cannot redirect a download to another publisher or host', () => {
  for (const apkUrl of [
    'https://evil.example/app.apk',
    'https://github.com/other/publisher/raw/' + 'a'.repeat(40) + '/app.apk',
    'https://github.com/alitrial2025/netflix-tv/raw/main/app.apk',
    'https://github.com/alitrial2025/netflix-tv/raw/' + 'a'.repeat(40) + '/app.apk?redirect=evil',
    '//evil.example/app.apk',
    'https://github.com.evil.example/alitrial2025/netflix-tv/raw/' + 'a'.repeat(40) + '/app.apk',
  ]) assert.throws(() => readRelease({ ...current('tv'), apkUrl }, 'tv', origin));
});
test('download context requires a declared build type and valid signing fingerprint', () => {
  for (const patch of [{ buildType: undefined }, { buildType: 'verified-safe' },
    { signingCertificateSha256: [] }, { signingCertificateSha256: ['unknown'] }]) {
    assert.throws(() => readRelease({ ...current('tv'), ...patch }, 'tv', origin));
  }
});
test('approved local APKs and project release URLs remain usable', () => {
  for (const apkUrl of ['/downloads/tv/1234-release.apk',
    'https://github.com/alitrial2025/netflix-tv/releases/download/v2.0/NetflixPro-TV.apk',
    'https://raw.githubusercontent.com/alitrial2025/netflix-tv/' + 'a'.repeat(40) + '/NetflixPro-TV.apk']) {
    assert.ok(readRelease({ ...current('tv'), apkUrl, buildType: 'release' }, 'tv', origin));
  }
});
test('other channels, credentials and invalid APK URLs fail closed', () => {
  assert.throws(() => readRelease(current('tv'), 'mobile', origin));
  for (const apkUrl of ['https://user:secret@github.com/app.apk', '/downloads/tv/../../index.html',
    '/assets/file.apk', 'http://github.com/app.apk', 'javascript:alert(1)']) {
    assert.throws(() => readRelease({ ...current('tv'), apkUrl }, 'tv', origin));
  }
});
