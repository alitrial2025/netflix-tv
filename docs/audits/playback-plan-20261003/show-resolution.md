# Show identity and episode-resolution investigation

Investigated 3 October 2026. Requests used no cookie jar, Cookie header or Authorization header. Only bounded public metadata responses were fetched in this investigation. Stop further provider/CDN probes after the shared audit's rate-limit sentinel; successful metadata does not prove playable segments today.

## Current behavior and regression

Both Android apps now use `PublicPlaybackResolver` for playback. The older warm/session resolvers remain in source for diagnostics but are not the active playback path. Netflix hosting IDs are validated against official Netflix title metadata; requested season IDs come from the public `seasonSelect`. Prime uses its public title-page state, with a special-case season map for Mr. Robot. Hotstar uses provider `post.php` season/episode metadata. All three then use the relevant provider `episodes.php`, followed by a fresh `playlist.php` and HLS validation.

The public title catalog contains show/movie identity candidates only. At the start of this investigation, season IDs were cached for one hour in memory for Netflix and episode mappings were not persisted. The final implementation adds scoped disk persistence. Consequently an official title page losing its public seasons can make an already-working provider-hosted show unresolvable.

Smallville is a concrete example: the official Netflix title page returned HTTP 200 and the correct title but had **no season selector**. Its JSON-LD declared `numberOfSeasons: 0`; its embedded requested-show data also declared an empty seasons connection. Another HTML parser cannot recover a season ID from this response. The existing Smallville unit fixture includes a selector and therefore does not model this real failure. The prior 2 October authorized warm audit independently resolved Smallville S3E1 to provider episode `82171122` and validated a video sample. That episode observation does not establish a complete season mapping.

There is also a fallback defect: missing public Netflix seasons throw `Netflix public seasons unavailable`, which the resolver's retry classification does not recognize. This stops alternative verified hosting-identity resolution. The same concern applies to `Prime public season metadata unavailable`. These errors should be distinct from ambiguous identity or authorization failures.

## Fresh observations

| Public metadata request | Result | What it establishes |
| --- | --- | --- |
| `net52.cc/mobile/post.php`, Stranger Things show ID `80057281` | HTTP 200, JSON authorization rejection | This Netflix metadata route requires authorization in this run. |
| Official Netflix Smallville title `70155584` | HTTP 200, correct title, zero seasons | Current official public seasons cannot supply provider season IDs. |
| Official Netflix Stranger Things title `80057281` | HTTP 200, five branded selector options | Public official metadata supplies explicit season IDs for this show. |
| `net52.cc/mobile/pv/episodes.php`, verified Mr. Robot S1/show ID | HTTP 200, ten labelled S1 episodes, more pages | Episode enumeration works without cookies for this verified Prime season. S1E1 matches `0RZED4V5SOLKXX2U04B6XONCIM`. |
| `net52.cc/mobile/hs/post.php`, Ironheart show `1271341039` | HTTP 200, title/type, season `1271341037`, labelled episodes | S1E1 maps to `1271341040` without cookies. |
| Official Prime Silo title `0GKY3CQGOYOPDSE76BWMJN5CK3` | HTTP 200, public season state | Official season state is present; segment delivery was not re-tested here. |
| Tested `episode.php` routes on net52 | HTTP 404 | These specific paths do not provide a usable public route. |
| `net53.cc` and `net53.in` | Proxy CONNECT HTTP 403 | This workspace cannot inspect those sites. No conclusion about their live DOM or application architecture is possible. |

Do not conclude all episodes or all providers are cookie-free from these observations. Metadata access and playable video/audio samples are separate acceptance stages. Provider catalog codes describe hosting namespaces and need not match a show's original distributor.

## Recommended work order

1. Fix narrow fallback classification for unavailable public season metadata. Add an actual missing-selector regression fixture and prove an exact alternative hosting identity is attempted. Preserve immediate authorization and rate-limit handling; reject ambiguous season/episode mappings.
2. Add a metadata-only persistent episode catalog shared in behavior by both apps. Key records by provider origin, catalog code, verified show ID and requested season. Store explicit season ID and episode-number-to-episode-ID mappings, canonical TMDB show identity, title/premiere identity evidence, provenance, schema/generation and timestamps. Prime IDs remain opaque strings. Never derive IDs arithmetically or number named seasons by row order.
3. Persist mappings after verified season-context and episode-uniqueness checks. A labelled default-season episode may be cached only for its actual labelled season; unlabelled rows require a verified season-specific response. Paginated responses must retain that season context, and duplicates with conflicting IDs must reject the mapping. Distinguish a completed season from a partial page.
4. Use valid local mappings first, then a bounded remote provider-scoped metadata feed, then public live discovery. If no authoritative public mapping exists, an authorized provider export/session can populate the metadata feed outside app startup. Start with observed missing shows and requested episodes; use checkpoints, bounded pages/concurrency, backoff and incremental refresh. A complete provider crawl is not an initial prerequisite.
5. Keep signed playback URLs ephemeral. Cached IDs still obtain a fresh issued playlist and validate the chosen video/audio path. A media-only CDN failure should not discard a verified show identity. An episode identity mismatch invalidates the scoped episode/season record; a proved show mismatch invalidates the title mapping. Keep a known-good feed snapshot on malformed, truncated or stale refreshes.
6. Separately verify deployment-time provider configuration and request-token changes outside the APK, with safe runtime validation and a known-good snapshot. The architecture investigation owns that work.

## Acceptance evidence

Offline cases should cover missing public seasons with a verified cached/provider-feed season, app/process restart reuse, changed provider origin isolation, incorrect S/E labels, ambiguous duplicate episodes, partial pagination, a newly added episode, feed rollback/truncation and stale mappings that cannot silently select another episode. Retain cookie-free request assertions.

After the provider cooldown, a small live matrix should include Smallville S3E1, Silo S1E1, Ironheart S1E1, Mr. Robot S1E1, a Netflix-branded multi-season show, a later season and an episode beyond the first page. Record separate results for show identity, season, episode, issued playlist, video manifest/sample and alternate audio. An HTTP 200 manifest alone is insufficient: the shared movie audit observed a rate-limit sentinel at the video-manifest stage.

Follow-up implementation adds `ProviderEpisodeCatalog` to both apps. The metadata-only file `verified_provider_episodes_v1.json` survives process/app restarts, has a seven-day TTL, and separates provider origin, catalog, show, season and episode. It holds at most 2,000 season records, 500 episodes per season and 8 MiB. Only explicit matching season/episode labels enter the episode cache. Signed URLs and credentials are not stored. Final integration follows the user’s no-warming direction: missing public seasons require verified cached/bundled metadata; no normal-session callback runs during playback. A failed cached episode receives one scoped rediscovery while its show, season and siblings remain available.

The remote episode feed/export is a subsequent deployment task. A proposed version-1 export uses `schemaVersion`, `generatedAt`, `providerOrigin`, and `rows`; each row contains `tmdbShowId`, `ott`, `showId`, `season`, `seasonId`, `verifiedAt`, `source`, and `episodes` (explicit `episode` and `id` pairs). The producer should write one immutable generation after verifying identity and season context, not append partially validated pages to the published snapshot. A `complete` marker and pagination counts distinguish complete seasons from partial mappings. The importer should accept only the configured provider origin and trusted feed source, enforce the same opaque-ID/label/budget rules, reject duplicate conflicting identities and rollback generations, and retain the last good snapshot. No remote importer or whole-provider scrape is deployed in this change.

No complete episode was downloaded, no session challenge was bypassed, and no net53 policy denial was routed around. Live segment delivery after the shared rate-limit response remains unverified.
