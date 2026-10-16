# Catalog availability repair — 16 October 2026

Reported on TV and mobile: Smallville, House of the Dragon and Red Notice fail with “can't play this title”.

## Confirmed defects and changes

Both production resolvers previously required a successful official Netflix public title page even for a typed TMDB → Netflix identity already in the catalog. A regional restriction, missing public page, or website failure could therefore prevent playback before requesting an otherwise available stream. A regression with a 503 public website reproduces this for Smallville and Red Notice.

Typed Netflix candidates now use the provider's live public search first. The response must confirm the exact native ID and title; conflicting explicit type, movie year, duplicate ID records, empty searches, and recommendation results cannot establish this match. The typed join supplies catalog type/identity; an untyped title-only match still requires the existing public-page verification. Exact season/episode checks and existing manifest/rate-limit validation remain in place. This change does not introduce a cookie session or modify issued CDN signatures.

House of the Dragon's Wikidata-derived ID `1260110208` currently returns HTTP 200 with null title/type/year and no usable episode records. The bundled public partner index publishes `1971002877`, and its live metadata supplies House Of The Dragon, type t, and explicit season IDs. Add the corrected candidate scoped to TMDB TV `94997` and premiere year 2022; title/type/episode validation still runs. The provider's generic year 2024 describes later availability and cannot replace the series premiere year. S1 uses season `1971009236`; S1E1 is episode `1971034981`. Default S3 episodes must never replace a requested S1 episode.

## Live checks and limits

On 16 October the normal issued playlist flow, without cookies or warming, returned valid HLS and 65,536-byte MPEG-TS video and separate audio samples for House of the Dragon S1E1 through `s30.freecdn31.top`. This is HTTP container evidence, not Android decoded-frame validation.

Smallville S1E1 resolves from its labelled live episode metadata to `82171078`. Red Notice resolves to `81161626`. Initial CDN requests failed before HTTP. Required test hosts were saved to the environment draft, and subsequent fresh probes succeeded: all three titles delivered actual 65,536-byte MPEG-TS video samples; Red Notice and House of the Dragon also delivered separate audio samples. No waiting-video or rate-limit signals were returned. The successful requests establish current connectivity; saving a draft alone does not apply runtime policy or publish the environment. Earlier transport failures do not establish a restriction on the user's network.

The live media audit starts from the exact catalog identities and verifies labelled episode metadata, ordinary issued playlists, preserved CDN URLs, manifests and bounded media samples. The production Kotlin resolver is covered separately by regression fixtures, including public-site failure and wrong title/type/year rejection. These are separate checks; no live Android first-frame claim is made.

Validation: 62 TV resolver/identity/episode/route tests, 61 mobile tests covering the same areas, and 22 Node playback/catalog checks passed with zero failures or skips. The initial Netflix regressions failed against the old code, then passed after the repair. Evidence and validation logs are retained outside the repositories under `/workspace/.netflix-tv-tools/review/two-shows-current`, with final live results in `media.json` and `media-final.log`. Release publication is tracked separately in the website update manifests. Full playback on a device remains unverified.
