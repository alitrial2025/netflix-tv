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
      badge.textContent = release.supportsUpdateGate ? 'Update Gate included' : 'Existing release · manual updates';
      link.textContent = channel === 'tv' ? 'Download TV APK' : 'Download phone APK';
    } else {
      summary.textContent = 'The next release is being prepared.';
      notes.textContent = 'The download will appear here when a signed APK is published.';
      badge.textContent = 'Coming soon';
      link.textContent = 'Release coming soon';
    }
    if (channel === 'tv') {
      document.querySelectorAll('.cta-download').forEach(cta => {
        cta.href = release?.apkUrl || '#downloads';
        if (release) cta.setAttribute('download', `NetflixPro-tv-${release.versionCode}.apk`);
        else cta.removeAttribute('download');
      });
      document.getElementById('heroRelease').textContent = release ? `TV version ${release.versionName}` : 'TV and phone downloads';
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
  const link = event.target.closest('[data-release-download], .cta-download');
  if (!link) return;
  if (link.dataset.unavailable === 'true') { event.preventDefault(); return; }
  if (link.hasAttribute('download')) status.textContent = 'Download requested. Check your browser’s downloads for progress.';
});
refresh();
