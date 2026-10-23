# Prime regional metadata repair — 23 October 2026

Reported selection: The Expanse (TMDB TV 63639), Season 1 Episode 1.

## Confirmed application defects

The provider search publishes Prime ID `0TZXL00XCKTUHFBO67U01EGMCX`, but Prime redirects its detail page to a regional route. The resolver disabled automatic redirects and did not explicitly follow Prime's redirects. Its season, homepage and public-search link parsers also excluded `/region/eu/detail/…` URLs. A captured official selector contains six explicitly numbered seasons; the previous Kotlin parser retained only the current season, while the Node parser rejected the selector entirely.

Both applications now accept official locale/region detail paths, including optional title slugs. Metadata redirects must preserve the exact native ID, HTTPS, the official host, standard port and absence of URL credentials. Redirects stop after three hops; loops and login, different-title and foreign-host redirects are rejected. If the default metadata page returns 502, 503 or 504, one explicit English detail request may recover the same identity. Authorization and rate-limit responses do not trigger that retry. Playback URLs and signatures are unchanged.

The Node diagnostics use the same URL rules, redirect bounds and desktop metadata headers. Season selectors remain scoped to the matching show's header instead of including recommendation selectors.

## Live evidence and remaining limitation

Search returns The Expanse with year 2015. A successful official regional metadata response identifies Season 1 and publishes seasons 1–6. Prime also intermittently returns HTTP 503 for these public detail routes; following a valid redirect does not guarantee upstream availability.

The provider's episode endpoint returned HTTP 200 without episode rows for the original Season 1 ID and the alternate Season 1 ID published by the regional selector. Of the six published regional season IDs checked, Season 5 returned ten correctly labelled episodes; the others returned no episode rows. Netflix search did not return an alternative Expanse title. NewTV requests returned HTTP 403 with a `Page Not Found` body and were not bypassed.

The user confirmed that S1E1 plays in NetMirror's own app. The empty cookie-free response therefore does not establish that the episode or its media is absent. Our catalogue lookup has not recovered the episode identity used by that app. The authenticated PV title endpoint returns `Invalid User` without a valid provider session. The retained legacy session implementation is diagnostic code and is not called by the current playback pipeline.

Live opt-in tests of the production Kotlin resolver reproduced the failure in both TV and mobile: the Prime detail redirect completed with HTTP 200, the requested Season 1 endpoint returned zero episode rows, and resolution ended with `Requested episode S1E1 is unavailable` before obtaining a playback URL. A separate bounded check of normal provider verification made one verification request and received no session cookie. The preceding request to `userver.net52.cc` was blocked by the environment proxy. This is not evidence that normal verification fails on the user's device.

A Node live audit of Season 5 resolved episode `0I5QBOS5FWA8IES045ZH7O06SN` and obtained an issued playlist and master. Its child CDN request failed to connect from this environment; no decoded-playback claim follows from that result. Season 1 has not been demonstrated playable in our apps. Missing episode identity must not be replaced with guessed IDs, another season, or an unrelated title.

The earlier Firestore diagnostic returned HTTP 429 `RESOURCE_EXHAUSTED`. The current unauthenticated availability probe instead returns HTTP 403 `PERMISSION_DENIED`, as expected for a protected document. This does not test a paid account or prove its membership admission succeeds.

## Validation

Before the repair, both new Android reproduction tests failed, and the Node test rejected the captured regional selector. Regression coverage now exercises all six season labels, regional homepage/search links, same-title redirects, malicious/different-title redirects, bounded loops, one locale fallback, no retries after authorization/rate responses, and a missing pilot that cannot reach playback. The normal identity, cross-catalog and resolver regression suites are run for both applications. Final validation passed 52 targeted Android tests in each app, plus 43 Node public-playback tests in TV and 35 in mobile. These counts describe code validation, not live source availability.

The subsequent broader Node run passed 138 TV/server tests, 42 mobile tests, and 27 TV/server Firestore emulator tests. These passing suites are separate from the two failing opt-in live S1E1 playback checks described above.

No app update is advertised as restoring The Expanse S1E1 until a real source and media sample can be verified.
