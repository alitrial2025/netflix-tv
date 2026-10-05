# Reliability and startup plan — 2026-10-05

Scope: Android phone and TV apps, public playback, downloads, billing, subscriptions and nearby account/network lifecycle bugs. Stages were followed in this order. Source changes and available automated checks are complete. Device startup and a controlled purchase remain release validation; they are not represented as passed.

## 1. Baseline and map — complete

- [x] Inspect repository status and instructions (both worktrees initially clean; no AGENTS.md found).
- [x] Map active public resolvers, player/data-source wiring, download playback, update gate, membership/device/screen validation, checkout and Firestore rules.
- [x] Inspect cloud runtime and Android/JVM/Firestore tooling.
- [x] Record confirmed defects, checks and runtime limits below.

## 2. Stream startup and request limits — complete in source

- [x] Serialize identical title/episode resolution and reuse its cache; unrelated titles remain independent. Cancellation releases the gate; existing expiry and source/session invalidation remain active.
- [x] Let a verified public identity finish without waiting for independent slow TMDB discovery; cancel unnecessary calls. Revalidate candidates when new aliases arrive.
- [x] Prefer existing indexed/native mappings before partner-page translation.
- [x] Hand already validated HLS masters/variants to the player's initial GET without fetching them twice. Cache is memory-only, one-use, 15 seconds, 16 entries/4 MiB, source-revision checked; segments and later refreshes use normal networking.
- [x] Restrict rate-limit matching to actual HTTP/error/playback evidence rather than titles, descriptions or public title/search HTML. Existing provider pacing, waiting-video handling, request budget and cooldown/Retry-After enforcement remain active.
- [x] Test duplicate/cache work, unrelated keys, cancellation, discovery race, manifest handoff/expiry, and real versus false rate-limit evidence.
- [x] Add phone Play-to-first-frame debug timing; retain TV runtime timing.

Evidence: the slow-TMDB fixture delays metadata by 3.5 seconds, while an already verified typed identity completes in about 0.5 seconds in the final suites. This is a resolver fixture, not live playback. The <10-second first-frame target has not been measured on a phone/TV against real providers.

## 3. Phone offline playback — complete in source

- [x] Permit local app access when Internet is not validated; restart the existing required-update flow when connectivity returns. Preserve installation/approval phases and compare observer events with the connectivity actually used by the check, covering initialization transitions.
- [x] Use a complete local download without an online screen-slot transaction or heartbeat. Missing offline-only files do not acquire a network lease.
- [x] Persist the exact previously server-confirmed account/device/plan/status/expiry needed for downloaded playback across process restart. Clear it on sign-out, restrictions, server deletion or denied entitlement reads.
- [x] Avoid erasing a matching prior confirmation on a transient online confirmation failure.
- [x] Exclude device/offline confirmation from backup/device transfer.
- [x] Verify offline update policy, persisted proof isolation/expiry, device policy, local HLS/captions and existing download recovery tests.

A downloaded-title cold launch in airplane mode and reconnection to a required update still need an actual-device check. Offline access uses previously confirmed entitlement; it cannot learn a new remote revocation until reconnection.

## 4. Payments and subscriptions — implemented and backend deployed

- [x] Map checkout → exact receipt evidence → atomic activation → phone/TV canonical membership reads.
- [x] Remove merchant authorization and membership-write authority from the phone. Checkout checks authenticated backend availability before taking payment and keeps pending account/plan references across restarts.
- [x] Build/deploy the selected Cloudflare Worker; provision a dedicated Firebase IAM role/account/key; upload both Firebase and PayHero credentials as server secrets through stdin.
- [x] Validate account/plan/reference, receipt uniqueness, status, incoming M-Pesa/KES evidence, whole sufficient amount, renewals and retries. Bound uploads and gateway lookup work.
- [x] Use one Firestore transaction for receipt consumption, canonical subscription and both mirrors; use an expiring nonce lock to avoid duplicate gateway lookups. Reject late expired operations and cross-account receipt races.
- [x] Validate Firebase JWT signatures/issuer/audience/expiry and account disabled/revocation state.
- [x] Deploy the matched protected rules once to the shared Firebase project, then read them back to verify an exact source match.
- [x] Verify live merchant access and an existing receipt lookup without redemption; verify live Firebase reads/user-status permission and a read-only Firestore transaction.
- [x] Check secret binding names, deployed unauthenticated rejection, production dependency audit and final phone endpoint/absence of merchant BuildConfig field.
- [x] Replace the release workflow's merchant secret input with a public backend URL override; default builds use the deployed endpoint and availability checks still protect an unavailable backend. The phone release configuration now honors the supplied signing key alias, matching TV behavior.
- [x] Correct payment form state/reference handling during account/auth changes and stop a fast device wall clock overriding freshly synchronized server time.

Backend: `https://netflixpro-membership.netflixpro-cca67.workers.dev`
Project: `netflixpro-cca67`
Dedicated identity: `netflixpro-payments@netflixpro-cca67.iam.gserviceaccount.com`
Operational details: phone repository `payment-server/README.md`.

The initial repository rules allowed client-issued entitlement writes while denying receipt writes. Read-only inspection showed production rules already protected memberships; that made the legacy phone activation path incompatible with production. The prepared server flow addresses this. Production now matches the prepared rules and denies client receipt/verification-lock access too. Firebase Cloud Functions listing is unavailable because the project's Cloud Functions API is disabled; it was not enabled and no paid Functions backend was created.

No live charge, receipt redemption or paid-membership mutation was performed. A controlled purchase on the updated phone must still verify authenticated availability, Lipwa reference round trip, canonical activation, same-code retry, renewal and TV refresh. Older APKs require the updated phone payment flow. Free-tier CPU/quota usage must be observed under actual traffic; source/tests do not certify 100% uptime or bug-free live payments.

## 5. Wider review and final verification — complete for available checks

- [x] Review nearby cancellation/account/source generations, cache freshness, local download selection, update transitions, proof lifecycle, subscription clock, device/screen policies, checkout account changes and server transaction boundaries.
- [x] Run targeted regression tests before broad suites; compile both apps, run both complete unit suites and lint.
- [x] Review shared-app consistency, diffs, secret placement and ignored local artifacts.
- [x] Record available checks and unresolved release validation separately.

| Check | Result |
|---|---|
| Phone `assembleDebug`, `testDebugUnitTest`, `lintDebug` | Passed: 204 tests passed, 10 skipped, no failures/errors |
| TV `assembleDebug`, `testDebugUnitTest`, `lintDebug` | Passed: 284 tests passed, 9 skipped, no failures/errors |
| Final HTML rate-limit/offline regressions + app rebuild/lint | Passed: phone 12 targeted tests, TV 10 targeted tests; build/lint passed in both |
| Worker `npm test` | 19 passed, none skipped |
| Firestore REST transactions + security rules emulator | 19 passed, none skipped; demo projects only |
| Worker dry-run and production deployment | Passed; both secrets configured; final bundle startup reported 8 ms (not request CPU or playback latency) |
| Production npm dependency audit | Zero reported vulnerabilities |
| Firebase identity/live reads/read-only transaction | Passed; no paid data changed |
| PayHero live merchant/receipt lookup | Passed; no payment made or redeemed |
| Production rules deploy/readback | Passed; exact match with both repositories |
| Endpoint authentication rejection | Passed: unauthenticated status request returns 401 |
| Phone build payment configuration | Clean build without local `.env` passed build/lint; APK contains the deployed URL and no merchant/server credential fields or service-account identity |
| Real-device <10s startup and offline/reconnect flow | Pending release validation |
| Controlled purchase/retry/renewal/TV refresh | Pending release validation |

The Android skips are existing opt-in film/motion capture and live TMDB cases; lint completes with existing warnings and no errors. Logs are in `/workspace/artifacts`, outside git. No release APK/update manifest was published.

## Release checks still required

1. Install updated test builds. For several cold/warm movie and episode starts, record the phone's `PlaybackTiming` / `NetMirror` debug logs and TV's `RuntimeTiming` markers. Check first frame against the <10s target and confirm no duplicate manifest GETs or same-title resolver bursts. Repeat on a slower connection and respect actual provider cooldowns.
2. With a valid completed download and previously confirmed account/device, cold-launch the phone offline, play and seek, then reconnect to a required-update manifest. Verify missing files, expired/changed membership and account switching remain gated appropriately.
3. Use an authorized controlled payment on the updated phone. Verify the checkout reference reaches merchant evidence, activation reaches both apps, retrying the same code cannot add time twice, wrong-account/plan codes are rejected, and a same-plan renewal preserves paid time.
4. Observe Worker CPU/error/quota metrics and Firebase quota use. Cloudflare Free avoids inactivity sleep but still has request/CPU limits and no 100% availability guarantee.
