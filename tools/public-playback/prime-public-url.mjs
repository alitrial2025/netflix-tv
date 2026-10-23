// Prime publishes locale, region and optional title-slug detail routes.
// These are metadata identities, never login, player or arbitrary redirect URLs.
export function primeDetailId(raw) {
  let url; try { url = new URL(raw, 'https://www.primevideo.com'); } catch { return null; }
  if (url.protocol !== 'https:' || !['primevideo.com', 'www.primevideo.com'].includes(url.hostname) ||
      url.port || url.username || url.password) return null;
  return url.pathname.match(/^\/(?:-\/[A-Za-z_-]+\/)?(?:region\/[A-Za-z0-9_-]{1,16}\/)?detail\/(?:[^/]+\/)?([A-Z0-9]{10,30})\/?$/)?.[1] ?? null;
}
