# Cookie-free playback update — 3 October 2026

## Scope and implementation

Three agents investigated movie routes, show identities, and runtime architecture across the TV and mobile apps. The uploaded ZERO_COOKIE_OTT_GUIDE.md supplies endpoint/token examples; its live-playback claims are reference evidence, not fresh verification. The user's final direction is no warming during playback.

1. Resolve only the three hosting catalogs (`nf`, `pv`, `hs`). A distributor's branding does not determine its hosting route.
2. Try cached/native catalog identities and public NF/PV search before slower public identity discovery. Complete movie search metadata must match exact title, release year, type and a valid native ID. Incomplete records require official public identity validation. Never call gated NF/PV post endpoints.
3. Discover NF/PV seasons from the original platforms' public pages. HS post supplies public show/season/episode metadata. Preserve opaque Prime IDs, explicit episode numbering and pagination. Reject wrong seasons and duplicate conflicting episode IDs.
4. Store verified season/episode metadata for seven days in an atomic file, scoped to provider origin, hosting catalog and show. Reuse it after restart. Supplementary assets add guide-native HS identities and all ten previously observed Smallville seasons, bound to exact title/origin/show. Confirm seeded seasons through live episode metadata. Never cache cookies or signed streams in the metadata catalog.
5. A failed cached episode gets one targeted rediscovery while sibling episodes and the verified show remain available. Obtain fresh playlists and preserve all issued stream URLs/audio groups/captions. Empty or HTTP-404 playlists can fall back to the guide's direct provider HLS entry. Authentication and rate-limit responses stop immediately.
6. Construct `HASH1::MD5(timestamp+contentId)::timestamp::ek::m` only for missing/unknown same-origin provider-master auth. Add the normal `hd=off`, `lang=eng`, `hp=yes` flags when absent. Preserve genuine provider-issued and CDN signatures, including `su` modes: a normal earlier diagnostic successfully decoded an issued `su` route, contradicting the guide's blanket rejection claim.
7. Refresh provider-origin/master hints in the background from the existing streaming configuration URL, with a stable snapshot per resolution, six-hour success TTL and thirty-minute failure backoff. Keep the last valid settings on failure. The source configuration is updated locally; no hosting deployment is included.

## Evidence and limits

Public NF/PV movie search and playlists, Prime Mr. Robot episodes, and HS Ironheart/Mulan post metadata were reachable without cookies. Current Smallville official HTML lacks season IDs despite the guide claiming ten; its verified earlier provider metadata supplies the bundled candidates. net53 could not be inspected because proxy CONNECT was denied.

The existing warm-to-end diagnostic previously decoded Smallville S4E8 (episode 82171151) in 41.4 seconds, including 38.1 seconds of session preparation. This is historical baseline evidence only; the updated apps do not perform that warming.

The direct guide Mulan request on 3 October returned the known /files/220884 rate-limit video with HTTP 200. A single follow-up after more than nine minutes, also checking raw token separators, returned the same sentinel. See guide-direct-mulan.json and guide-direct-raw-mulan.json. At the user’s request, a bounded Lanterns S1E1 check also verified public identity/episode/playlist metadata but received the same rate-limit video at HLS. See guide-lanterns.json. Further media probes stopped. Cookie-free decoded playback and Android first-frame timing are therefore not newly verified by this update. Parser/API incompatibility and missing upstream metadata cannot be repaired merely by updating the provider host.

## Validation

See validation.md for final unit/lint/build results and APK versions. Offline regression checks cover exact/mismatched movie identity, opaque Prime IDs, public season parsing, scoped persistence/restart reuse, stale episode recovery, correct headers/no cookies, direct provider-token fallback, issued-signature preservation, runtime snapshot stability and rate-limit handling.
