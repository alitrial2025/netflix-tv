# Catalog availability repair — 16 October 2026

Reported on TV and mobile: Smallville, House of the Dragon and Red Notice fail with “can't play this title”.

## Confirmed defects and changes

Both production resolvers previously required a successful official Netflix public title page even for a typed TMDB → Netflix identity already in the catalog. A regional restriction, missing public page, or website failure could therefore prevent playback before requesting an otherwise available stream. A regression with a 503 public website reproduces this for Smallville and Red Notice.

Typed Netflix candidates now use the provider's live public search first. The response must confirm the exact native ID and title; conflicting explicit type, movie year, duplicate ID records, empty searches, and recommendation results cannot establish this match. The typed join supplies catalog type/identity; an untyped title-only match still requires the existing public-page verification. Exact season/episode checks and existing manifest/rate-limit validation remain in place. This change does not introduce a cookie session or modify issued CDN signatures.

House of the Dragon's Wikidata-derived ID `1260110208` currently returns HTTP 200 with null title/type/year and no usable episode records. The bundled public partner index publishes `1971002877`, and its live metadata supplies House Of The Dragon, type t, and explicit season IDs. Add the corrected candidate scoped to TMDB TV `94997` and premiere year 2022; title/type/episode validation still runs. The provider's generic year 2024 describes later availability and cannot replace the series premiere year. S1 uses season `1971009236`; S1E1 is episode `1971034981`. Default S3 episodes must never replace a requested S1 episode.

## Live checks and limits

On 16 October the normal issued playlist flow, without cookies or warming, returned valid HLS and 65,536-byte MPEG-TS video and separate audio samples for House of the Dragon S1E1 through `s30.freecdn31.top`. This is HTTP container evidence, not Android decoded-frame validation.

Smallville S1E1 resolves from its labelled live episode metadata to `82171078`. Red Notice's issued playlist and provider master also return HTTP 200 with real CDN variants. Neither response showed a waiting-video or rate-limit signal. Further media checks are blocked by the cloud proxy before HTTP to `s23.nm-cdn9.top` and `s20.freecdn1.top`. Public Netflix and Airtel pages are also blocked by the cloud proxy. These cloud restrictions do not establish a failure or restriction on the user's network. The exact hosts were added to the reusable environment draft; saving does not apply runtime policy or publish it.

Evidence and validation logs are retained outside the repositories under `/workspace/.netflix-tv-tools/review/two-shows-current`. No new release APK is published by this repair. Full Netflix playback on a device remains unverified.
