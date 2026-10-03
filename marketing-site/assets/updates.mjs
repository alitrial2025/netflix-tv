import { readRelease, releaseSummary } from './release-policy.mjs';

const retry = document.getElementById('releasesRetry');
const status = document.getElementById('downloadStatus');
let loading = false;

function configureLink(link, release) {
  if (release) {
    link.href = release.apkUrl;
    link.setAttribute('download', `NetflixPro-${release.channel}-${release.versionCode}.apk`);
    link.removeAttribute('aria-disabled');
    link.dataset.unavailable = 'false';
  } else {
    link.href = '#downloads';
    link.removeAttribute('download');
    link.setAttribute('aria-disabled', 'true');
    link.dataset.unavailable = 'true';
  }
}

async function loadChannel(channel) {
  const card = document.querySelector(`[data-release-card="${channel}"]`);
  const summary = card.querySelector('[data-release-summary]');
  const notes = card.querySelector('[data-release-notes]');
  const link = card.querySelector('[data-release-download]');
  const badge = card.querySelector('[data-release-badge]');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 6000);
  summary.textContent = 'Checking the latest release…';
  try {
    const response = await fetch(`updates/${channel}.json`, { cache: 'no-store', signal: controller.signal });
    if (!response.ok) throw new Error('Release check failed');
    const text = await response.text();
    if (new TextEncoder().encode(text).length > 65536) throw new Error('Release metadata too large');
    const release = readRelease(JSON.parse(text), channel, location.origin);
    configureLink(link, release);
    if (release) {
      summary.textContent = releaseSummary(release);
      notes.textContent = release.releaseNotes || 'Download the latest published release.';
      badge.textContent = release.buildType === 'debug' ? 'Debug review build · outside Google Play' : 'Release build · outside Google Play';
      link.textContent = `Download ${channel === 'tv' ? 'TV' : 'phone'} ${release.buildType === 'debug' ? 'debug ' : ''}APK`;
      let details = card.querySelector('.release__identity');
      if (!details) {
        details = document.createElement('details');
        details.className = 'release__identity';
        card.insertBefore(details, link);
      }
      details.replaceChildren();
      const heading = document.createElement('summary');
      heading.textContent = 'File source and verification details';
      const identity = document.createElement('p');
      identity.textContent = `Developer: mzazimhenga · Source: ${new URL(release.apkUrl).hostname}\nPackage: ${release.packageName}\nSHA-256: ${release.sha256}\nSigning certificate SHA-256: ${release.signingCertificateSha256.join(', ')}`;
      identity.style.whiteSpace = 'pre-line';
      details.append(heading, identity);
    } else {
      summary.textContent = 'The next release is being prepared.';
      notes.textContent = 'The download will appear here when a signed APK is published.';
      badge.textContent = 'Coming soon';
      link.textContent = 'Release coming soon';
    }
    // Hero links stay on the download section, where build context is visible.
    const heroReleaseEl = document.getElementById('heroRelease');
    if (heroReleaseEl) {
      const tvCard = document.querySelector('[data-release-card="tv"] [data-release-summary]');
      const mobileCard = document.querySelector('[data-release-card="mobile"] [data-release-summary]');
      const tvMatch = tvCard?.textContent.match(/Version ([^\s·]+)/);
      const mobileMatch = mobileCard?.textContent.match(/Version ([^\s·]+)/);
      if (tvMatch && mobileMatch) {
        heroReleaseEl.textContent = `TV v${tvMatch[1]} · Phone v${mobileMatch[1]}`;
      } else if (tvMatch) {
        heroReleaseEl.textContent = `TV version ${tvMatch[1]}`;
      } else if (mobileMatch) {
        heroReleaseEl.textContent = `Phone version ${mobileMatch[1]}`;
      }
    }
    return true;
  } catch {
    configureLink(link, null);
    summary.textContent = 'Downloads are temporarily unavailable.';
    notes.textContent = 'Check your connection and try again.';
    badge.textContent = 'Please retry';
    link.textContent = 'Unavailable';
    if (channel === 'tv') document.querySelectorAll('.cta-download').forEach(cta => {
      cta.href = '#downloads'; cta.removeAttribute('download');
    });
    if (channel === 'mobile') document.querySelectorAll('.cta-download-mobile').forEach(cta => {
      cta.href = '#downloads'; cta.removeAttribute('download');
    });
    return false;
  } finally { clearTimeout(timeout); }
}

async function refresh() {
  if (loading) return;
  loading = true;
  retry.hidden = true;
  const results = await Promise.all([loadChannel('tv'), loadChannel('mobile')]);
  retry.hidden = results.every(Boolean);
  loading = false;
}

retry.addEventListener('click', refresh);
document.addEventListener('click', event => {
  const link = event.target.closest('[data-release-download], .cta-download, .cta-download-mobile');
  if (!link) return;
  if (link.dataset.unavailable === 'true') { event.preventDefault(); return; }
  if (link.hasAttribute('download')) status.textContent = 'Download requested. Check your browser’s downloads for progress.';
});
refresh();
