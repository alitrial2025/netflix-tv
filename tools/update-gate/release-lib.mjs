import { inflateRawSync } from 'node:zlib';

export const PACKAGES = Object.freeze({ tv: 'com.netflixprotv.apk', mobile: 'com.netflixpro.apk' });
export const MAX_APK_BYTES = 512 * 1024 * 1024;

export function secureUrl(raw) {
  const url = new URL(raw);
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.hash || url.port) {
    throw new Error('Use a public HTTPS URL without credentials, a fragment or a custom port.');
  }
  return url;
}

export function parseBadging(text, channel) {
  if (!PACKAGES[channel]) throw new Error('Channel must be tv or mobile.');
  const identity = text.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/m);
  const min = text.match(/^(?:minSdkVersion|sdkVersion):'(\d+)'/m);
  const target = text.match(/^targetSdkVersion:'(\d+)'/m);
  if (!identity || !min || !target) throw new Error('Could not read APK version/SDK metadata.');
  const [packageName, versionCode, versionName] = [identity[1], Number(identity[2]), identity[3]];
  const minSdk = Number(min[1]);
  if (packageName !== PACKAGES[channel]) throw new Error(`Wrong package for ${channel}: ${packageName}`);
  if (!Number.isSafeInteger(versionCode) || versionCode < 1 || versionCode > 2147483647 || !versionName || versionName.length > 80) {
    throw new Error('Invalid APK version.');
  }
  if (minSdk < 24 || minSdk > 100) throw new Error('Unsupported minimum Android SDK.');
  if (/^package: .*\bsplit=/m.test(text)) throw new Error('Publish a standalone APK, not a split APK.');
  return { packageName, versionCode, versionName, minSdk, targetSdk: Number(target[1]) };
}

export function parseSigners(text) {
  if (/CN=Android Debug\b/i.test(text)) throw new Error('Use a release-signed APK, not an Android debug APK.');
  const signers = [...text.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})\s*$/gmi)]
    .map(match => match[1].toLowerCase()).sort();
  if (!signers.length || new Set(signers).size !== signers.length) throw new Error('APK signing certificate could not be verified.');
  return signers;
}

// Only reads the small bundled JSON config from an already-built APK. Does not extract files.
export function readGateConfig(apk) {
  let end = -1;
  for (let i = apk.length - 22; i >= Math.max(0, apk.length - 65557); i--) {
    if (apk.readUInt32LE(i) === 0x06054b50 && i + 22 + apk.readUInt16LE(i + 20) === apk.length) { end = i; break; }
  }
  if (end < 0) throw new Error('Invalid APK ZIP directory.');
  const entries = apk.readUInt16LE(end + 10);
  let offset = apk.readUInt32LE(end + 16);
  for (let i = 0; i < entries; i++) {
    if (offset + 46 > apk.length || apk.readUInt32LE(offset) !== 0x02014b50) throw new Error('Invalid APK entry.');
    const length = apk.readUInt16LE(offset + 28);
    const name = apk.subarray(offset + 46, offset + 46 + length).toString('utf8');
    if (name === 'assets/update-gate.json') {
      const method = apk.readUInt16LE(offset + 10);
      const compressed = apk.readUInt32LE(offset + 20);
      const size = apk.readUInt32LE(offset + 24);
      const local = apk.readUInt32LE(offset + 42);
      if (size > 4096 || compressed > 8192 || local + 30 > apk.length || apk.readUInt32LE(local) !== 0x04034b50) {
        throw new Error('Invalid Update Gate config.');
      }
      const data = local + 30 + apk.readUInt16LE(local + 26) + apk.readUInt16LE(local + 28);
      if (data + compressed > apk.length) throw new Error('Truncated Update Gate config.');
      const payload = apk.subarray(data, data + compressed);
      const json = method === 0 ? payload : method === 8 ? inflateRawSync(payload, { maxOutputLength: 4096 }) : null;
      if (!json || json.length !== size) throw new Error('Unsupported Update Gate config compression.');
      return JSON.parse(json.toString('utf8'));
    }
    offset += 46 + length + apk.readUInt16LE(offset + 30) + apk.readUInt16LE(offset + 32);
  }
  return null;
}

export function makeRelease({ channel, metadata, bytes, sha256, signers, apkUrl, notes = '', previous, gateEnabled }) {
  if (!Number.isSafeInteger(bytes) || bytes < 1 || bytes > MAX_APK_BYTES || !/^[0-9a-f]{64}$/.test(sha256)) {
    throw new Error('Invalid APK size or SHA-256.');
  }
  if (metadata.packageName !== PACKAGES[channel]) throw new Error('Package/channel mismatch.');
  if (!(apkUrl.startsWith('/') && !apkUrl.startsWith('//'))) secureUrl(apkUrl);
  if (notes.length > 2000) throw new Error('Release notes must be 2,000 characters or fewer.');
  if (previous?.available) {
    if (metadata.versionCode <= previous.versionCode) throw new Error('Increase versionCode before building the next release.');
    if (JSON.stringify(signers) !== JSON.stringify([...previous.signingCertificateSha256].sort())) {
      throw new Error('Signing key changed. Use the original release keystore.');
    }
  }
  return {
    schemaVersion: 1, channel, packageName: metadata.packageName, available: true,
    ...metadata, sizeBytes: bytes, sha256, signingCertificateSha256: signers,
    apkUrl, releaseNotes: notes.trim(), supportsUpdateGate: gateEnabled,
    publishedAt: new Date().toISOString()
  };
}
