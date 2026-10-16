# Independent playback fallback — 16 October 2026

The user's ZERO_COOKIE_OTT_GUIDE.md describes an independent NewTV service before provider fallback. Its availability statements are research observations, not current runtime guarantees. The app previously attempted NewTV only after resolving metadata through the older provider/public platform pages, so a failure in that prerequisite could prevent both optional routes from running.

The TV and mobile resolvers now start from their typed TMDB/native catalog candidates and verified metadata cache:

1. A cold movie can try NewTV before any Netflix website or net52 metadata request. The returned movie title must match; conflicting type, episode labels and release year are rejected before fetching video. Previously verified movies can reuse their identity.
2. A cached episode can try NewTV immediately using its exact provider-scoped show/season/episode ID, including after an app restart. A cold episode can use NewTV's own `/newtv/post.php?id=<show>` and `/newtv/episodes.php?id=<season>&page=<page>` contracts, taken from the referenced upstream NetflixMirrorProvider implementation. Title/type, explicit season labels, episode numbers, opaque native IDs, pagination and ambiguous mappings are checked. A series ID, default latest season, list position, or another cached episode cannot substitute for the requested episode.
3. Verified NewTV movie and episode metadata is retained before HLS validation, so an empty/malformed CDN URL or a NewTV player failure does not discard the identity needed by the next route. The unsigned direct HLS route remains second and the original issued-playlist resolver remains third. All verified episodes returned for the requested season are retained, so Next episode can reuse them across resolver/app restarts. Exact IDs survive without saving cookies or signed stream URLs in the metadata catalog.

All optional operations share the existing playback deadline and preserve the 18-second reserve for the issued route. Each NewTV operation is capped at four seconds and the direct probe at two. NewTV and the existing provider retain separate rate-limit state. A healthy NewTV route can serve a known movie/episode while the older provider is unavailable or cooling down; a NewTV 429 does not block the issued provider. Player and catalog HTTP 403/404 responses have separate 60-second availability backoffs, so a rejected player does not disable a healthy episode catalog. Existing cancellation remains active.

This is metadata and route independence, not guaranteed availability. An uncatalogued title still needs a verified identity, and a first-time episode needs actual labelled episode metadata from a responding catalog. No episode IDs or CDN locations are guessed. The guide's CAPTCHA/token bypass suggestions are not required by this repair; existing issued CDN URLs remain unchanged.

## Evidence

Three new regressions failed against the earlier resolver: cold movie with unavailable legacy metadata, cold movie during a legacy cooldown, and a previously verified episode after restart with unavailable metadata. They passed after the route ordering was corrected. Additional integration coverage checks cold independent series metadata, pagination, cache restoration, wrong season/title/type, ambiguous episode IDs, unavailable optional routes, the unchanged issued URL, and an independent catalog remaining usable after its player fails.

Live requests to the guide's NewTV player and catalog endpoints returned HTTP 403 `Page Not Found` with no Retry-After on 16 October. The native integration cases therefore use deterministic responses to verify fallback behavior; they do not establish that the external NewTV service is currently healthy. Separate live issued-route checks in `docs/catalog-availability-20261016.md` delivered MPEG-TS samples for Red Notice, Smallville S1E1 and House of the Dragon S1E1. Those samples are not Android first-frame evidence.

Detailed logs and receipts are outside the repositories at `/workspace/.netflix-tv-tools/review/fallback-independence`. This source repair does not publish replacement release APKs.
