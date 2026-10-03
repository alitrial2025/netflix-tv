const packages = { tv: 'com.netflixprotv.apk', mobile: 'com.netflixpro.apk' };

export function readRelease(value, channel, origin) {
  if (!value || value.schemaVersion !== 1 || value.channel !== channel || value.packageName !== packages[channel]
      || typeof value.available !== 'boolean') throw new Error('Invalid release metadata');
  if (!value.available) return null;
  if (!Number.isSafeInteger(value.versionCode) || value.versionCode < 1 || value.versionCode > 2147483647
      || typeof value.versionName !== 'string' || !value.versionName.trim() || value.versionName.length > 80
      || !Number.isSafeInteger(value.sizeBytes) || value.sizeBytes < 1 || value.sizeBytes > 512 * 1024 * 1024
      || !Number.isInteger(value.minSdk) || value.minSdk < 24 || value.minSdk > 100
      || typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256)
      || !Array.isArray(value.signingCertificateSha256) || value.signingCertificateSha256.length < 1
      || value.signingCertificateSha256.some(hash => typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash))
      || typeof value.apkUrl !== 'string') throw new Error('Invalid release metadata');
  const raw = value.apkUrl;
  const relative = raw.startsWith('/') && !raw.startsWith('//');
  if (!relative && !raw.startsWith('https://')) throw new Error('Invalid download URL');
  const url = new URL(raw, origin);
  if (url.username || url.password || url.hash || url.port && !relative) throw new Error('Invalid download URL');
  if (!relative && url.protocol !== 'https:') throw new Error('HTTPS download required');
  const project = 'alitrial2025/(?:netflix-tv|netflix-mobile)';
  const pinnedGithub = url.hostname === 'github.com'
    && new RegExp(`^/${project}/(?:raw/[a-f0-9]{40}|releases/download/[a-zA-Z0-9._-]+)/[^/]+\\.apk$`).test(url.pathname);
  const pinnedRaw = url.hostname === 'raw.githubusercontent.com'
    && new RegExp(`^/${project}/[a-f0-9]{40}/[^/]+\\.apk$`).test(url.pathname);
  const localApk = url.origin === origin && /^\/downloads\/(?:tv|mobile)\/[a-zA-Z0-9._-]+\.apk$/.test(url.pathname);
  if (url.search || !(pinnedGithub || pinnedRaw || localApk)) throw new Error('Untrusted download source');
  if (!['debug', 'release'].includes(value.buildType)) throw new Error('Build type required');
  return { ...value, apkUrl: url.href, versionName: value.versionName.trim(),
    releaseNotes: typeof value.releaseNotes === 'string' ? value.releaseNotes.slice(0, 2000) : '',
    supportsUpdateGate: value.supportsUpdateGate === true };
}

export function releaseSummary(release) {
  return `Version ${release.versionName} · ${(release.sizeBytes / 1048576).toFixed(1)} MB`;
}
