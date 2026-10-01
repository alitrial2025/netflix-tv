# Provider/session and direct CDN audit — 1 October 2026

A fresh normal native handshake took **37.255s**. The session then resolved Smallville **S4E8** and verified two media segment samples in **3.873s**; the cached repeat took **1.265s**. A later run decoded one real H.264 frame from the first segment: new-title lookup with the saved session took **2.989s**, cached direct-CDN repeat **1.056s**. These are Node/curl measurements from this workspace, not Android first-frame timings.

| Run | Session generation | Result | Total |
| --- | --- | --- | --- |
| Mr. Robot S1E1, cold |37.255s, one handshake | Provider lookup succeeds; direct CDN rejects route |39.498s |
| Smallville S4E8, same session | None | HLS +2 MPEG-TS samples validated |3.873s |
| Mr. Robot S1E1, cached repeat | None | Same CDN-specific failure |0.653s |
| Smallville S4E8, cached direct CDN repeat | None | HLS +2 MPEG-TS samples validated |1.265s |

Smallville's exact requested episode maps to contentId 82171151. The provider returned a mobile HLS source leading to s23.nm-cdn9.top. Segment URLs have.jpg suffixes, but their bytes are MPEG-TS/H.264. In the optional decoded run, ffmpeg decoded a real sample frame. The imported-session run's cookie jar remained empty: authenticated provider calls used explicit normal session cookies; CDN manifest and media sample calls sent no cookies. Signed provider URLs still supply CDN authorization.

## Confirmed TV compatibility problem

The TV resolver extracted signatures using an `::ek` assumption, then reconstructed them. Current provider signatures include `::su` and `::su::myes`. A controlled Smallville comparison used the same host/path/session:

- Original provider signature tail `::su::myes`: HTTP 200, valid HLS,748ms.
- Previous TV reconstruction tail `::ek::su`: HTTP 200, an authorization-error body instead of HLS,609ms.

That demonstrates a client-side signature corruption problem even when the server returns200. It does not show that new arbitrary cookies or client-generated signatures can replace the provider handshake.

The TV changes preserve the complete provider-issued signature and its host, path, quality and unrelated query parameters. Audio/video URI signatures remain independent. Cached routes go directly to their actual CDN until the original token expiry; unfamiliar signature tails remain opaque. New episodes use one normal route discovery instead of guessing their host from another episode. Valid empty caption results are cached. Optional TMDB, subtitle/master metadata and audio probes have three-second call budgets, while required media fetching retains its normal behavior. Provider Origin/Referer follow the current authenticated domain. Expired cached master signatures trigger metadata refresh.

A CDN route rejection now retries route discovery once with the existing cookie. A positive rejection from an authenticated provider endpoint still triggers normal cookie renewal. Rate limits retain the existing cooldown/Retry-After policy. CDN changes in provider-issued URLs can be followed without hardcoding a new host, filename or signature mode into an APK; an incompatible provider authentication/API change may still require an app update.

## Remaining Mr. Robot failure

The current provider's PV search/post/episode/master endpoints return valid results, but s10.freecdn43.top rejects its 720p playlist with 403. Response headers did not contain the workspace proxy's blocked-reason marker. The same session plays Smallville, so this is insufficient evidence to invalidate the entire ten-hour cookie session. Mr. Robot availability in another phone/session is compatible with a CDN-specific or service-side difference. This audit has not isolated that server-side restriction or demonstrated current Mr. Robot playback.

## Reproduction and evidence

- [Reusable Node probe](../../tools/cdn-audit/README.md)
- [Cold/shared-session/cached-repeat stages](cold-and-warm.json)
- [Decoded sample stages](decoded-warm.json)
- [Controlled signature comparison](token-comparison.json)

Reports are sanitized. Raw cookies, signed URLs, response bodies and segment samples stay outside the repositories in0700/0600 private files. Kotlin tests cover exact signatures, future tails, custom CDN paths/hosts, original expiry, waiting-video rejection, warm reuse with no handshake and retaining the cookie after a bounded CDN route refresh. Node fixtures cover normal handshake/exact S4E8 resolution, direct cached repeat, report redaction and rejecting HTML/malformed media. Android decoder/first-frame timing and live post-change TV playback remain separate runtime checks.

## Standalone constructed-token test before APK publication

A separate controlled test used the normal issued Smallville route and no CDN cookies:

| Token case | Actual result | Time |
| --- | --- | --- |
| Original issued provider signature | Valid HLS, two MPEG-TS samples, one decoded frame |1.335s |
| Constructed byte-identically from the issued fields | Valid HLS, two MPEG-TS samples, one decoded frame |1.177s |
| Existing public client provider-master formula used at the CDN | HTTP 200 authorization-error body; no HLS |0.609s |

[Full sanitized results](constructed-tokens.json) include stage timings. The second case proves that preserving/reassembling issued fields works. The third case shows that the public client formula used for provider-master requests does not independently authorize this CDN route. This test provides no independent CDN signing key/token service. It completed before publishing a new TV APK.

## Final source validation

The complete TV unit suite passed: **184 tests, 0 failures, 0 errors, 9 skipped** (175 passed). Two Node fixture tests passed; both probe modules passed syntax checks. Debug compilation/assembly and Android lint passed. These checks do not measure Android live first-frame latency. The compiled TV review APK has not been published during this standalone-token investigation.

For independent token issuance, use the [draft provider integration request](provider-integration-request.md). It has not been sent; provider API access is not yet available.
