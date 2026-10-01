# Playback audit of 8c3de6d (TV) and 761ff24 (mobile)

The user reports that Mr. Robot (2015 thriller series) works on both their phone and TV. The cloud results below do not contradict that device observation: they describe this workspace's request path. No title is declared broken because a cloud CDN request fails.

## Confirmed client bugs and corrections

- TV compared every cached signature's mode with one global last-seen mode. Different titles and independently signed audio can use different modes. The resolver now trusts each provider-issued URL until its own expiry and retains its entire signature, path and query. Unsigned routes stay unsigned instead of receiving another title’s nonce.
- TV fetched optional marketing configuration before checking the stream cache. It now runs through the ViewModel independently of warming/playback. Failed checks back off and concurrent checks share one request.
- Remote token hints cleared good routes and attempted to adapt a changed master constant by merely evicting caches. Hints now configure only the public provider-master request, never invalidate signed media, and use `/updates/streaming.json`. The prior file was placed under `public/updates/tv.json`, which the root Vercel rewrite did not serve. The existing APK update manifest stays separate.
- CDN signature modes and the master request mode are separate. `tokenHint.masterMode` describes the latter; observing another title's CDN mode cannot change the master request protocol. A Qury extracted from the provider page takes precedence over remote fallback hints.
- TV's route retry cleared every other title's route and metadata. It now evicts the failed content, retains valid login state, and serializes persistent route updates.
- Unexpected HTTP-200 provider pages and already expired media signatures no longer trigger a blind full session renewal. Explicit provider authentication rejection still renews once; direct CDN rejection refreshes the route.
- Mobile treated playlist JSON containing `in=unknown` as an expired session. JSON placeholders now continue through the normal public master request flow, retaining OTT, episode cookie and unrelated query fields.
- Mobile stopped at a rejected provider cookie. Resolution now has one bounded renewal/retry. A late old-cookie rejection preserves a newer stored cookie. New handshakes clear the instance's older cookie jar before polling.
- Mobile queued CDN manifests behind provider metadata. Only provider origins/cookie-bearing requests use that queue; CDN/TMDB requests remain cancellable and respect rate-limit cooldowns.
- Mobile cached links against local fetch time without bounding their actual signed timestamps. Expiry now accounts for original video/audio signatures and provider-session lifetime. Playback errors evict the failed stream, preserving the login handshake.
- Mobile's latest Home commit used an unsupported `LazyColumn` parameter and could not compile. That argument is removed, retaining its other scroll changes.

## Reproducible Node checks

TV: `node tools/cdn-audit/audit-tv.mjs --private-dir /tmp/tv-private --report /tmp/tv-report.json --decode-sample`

Mobile (in its repository): `node tools/cdn-audit/audit-mobile.mjs --private-dir /tmp/mobile-private --report /tmp/mobile-report.json --decode-sample`

Both: `node --test tools/cdn-audit/probe.test.mjs tools/cdn-audit/audit-profiles.test.mjs`

Add `--session-file /tmp/previous-private/session.json` to measure an existing valid session. Each script targets Mr. Robot 2015 S1E1 and Smallville 2001 S4E8. They perform the ordinary provider handshake, exact season/episode lookup, master/video/audio traversal, bounded segment samples and optional FFmpeg decoding. TV retains the direct route with audio metadata on repeat; mobile retains its provider entry URL and OTT/episode cookies. Exact-app-header retries preserve the issued URL. No private signing key or independently forged CDN authorization is used.

The seven controlled Node tests cover JSON placeholders vs real auth failure, selected/default audio, independent signatures, exact S4E8 cookies, cookie isolation, bounded provider-cookie renewal, cached TV vs mobile paths, original token expiry, rate-limit bodies, malformed media, and CDN rejection preserving a warm session. Kotlin regressions verify the actual resolver behavior; Node is a protocol harness, not a substitute for those tests.

## Live evidence, 1 October 2026

| Check | TV script | Mobile script |
| --- | --- | --- |
| Initial normal handshake | 37.589 s | 37.904 s |
| Smallville S4E8 after handshake | 3.929 s | 2.940 s |
| First Smallville repeat | 1.112 s | 1.305 s |
| Final verification, existing session | 4.312 s | 4.095 s |
| Final verification repeat | 1.787 s | 1.899 s |
| Mr. Robot 2015 | Provider master HTTP 200; `s10.freecdn43.top` video HTTP 403 | Same cloud-only observation |

Smallville fetched real video/audio samples and decoded a sample video frame. Repeats generated no new handshake. The later runs overlapped Gradle validation and are recorded without presenting them as Android UI performance. Initial and final sanitized reports are committed alongside this document.

Both scripts selected Mr. Robot's exact 2015 catalog match, ID `0L52QDYY6OG738LB7ILP0VB7R4`. Preserving OTT/episode cookies and retrying the unchanged issued CDN URL with the respective app headers did not resolve this workspace's HTTP 403. There was no reported proxy-block header. That does not establish the cause: egress address, CDN edge policy, TLS/client behavior, request context or a different device session may explain the discrepancy. A device trace is still needed to isolate it; repeatedly renewing a good cookie on this observation is not justified.

Cookies, signed URLs, response bodies and media samples remain under private `/tmp` directories with 0700/0600 permissions. Reports contain host/path, query names, timings and status only. These APK changes still need Android/physical-device playback verification; Node/FFmpeg measures HTTP and sample decoding, not ExoPlayer first frame, D-pad responsiveness or ten-hour real-time endurance.

## Final app validation

`gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=2` passed for this repository. 195 tests: 186 passed, 9 skipped; zero failures/errors. Both debug APK builds and lint completed successfully. Each repository’s Node suite passed seven tests. Existing lint warnings remain; this does not claim physical-device playback validation.
