# Kids character artwork integration

## Connected

- `KidsHeroSection` uses the bundled `kids_characters/catalog.json`, exact
  movie/TV TMDB filenames, and IMDb artwork aliases. A title's character is
  independent of its poster, backdrop, and logo.
- TMDB movie and television `external_ids` endpoints supply missing IMDb IDs.
  The lookup runs only when an IMDb-only cutout could be matched, after rendered
  Home and quiet input. It has a four-second timeout, serializes requests,
  deduplicates concurrent requests, and keeps at most 64 cached identities.
- Successful IDs are cached for seven days in memory; absent IDs for a day;
  temporary failures for one minute. Cancellation is propagated, including an
  outer screen timeout, so leaving Home does not cache a false missing result.
- Four official transparent reference images are bundled: Bluey, Bingo for
  Bluey Minisodes, and each film's Poppy for Trolls World Tour/Band Together.
  Their combined source-file size is about 1.86 MiB. Decode size is constrained
  to the displayed character slot and keeps alpha. These titles add no artwork
  requests to cold startup and retain the existing card/focus layout.
- A local TMDB file takes priority. A registered local file, qualified IMDb
  filename, or explicit direct HTTPS image can be matched. Similar title names
  and overlapping movie/TV numbers do not substitute another character.

## Sources and limits

TMDB identities were verified live through its API on 2026-09-28, including
Bluey (2018) versus the unrelated earlier show with the same name. Official
character pages and their original image addresses are recorded in the asset
catalogue. Website HTML is diagnostic evidence, not parsed by the TV app.

Netflix Media Center is listed as awaiting title-specific cutouts; it is not an
automatic character-image API. No public pack of the exact Netflix Kids TV
cutouts was found. IMDb external IDs identify titles, not transparent artwork.
Add licensed files or explicit artwork entries for additional titles. As of
2026-09-29, Kids Home prioritizes these four registered titles. Bundled names and
cutouts appear before network metadata; matching catalogue entries or TMDB detail
responses supply descriptions and backdrops. Larger character slots preserve
alpha, and metadata enrichment retains the same movie/TV identities.

Public reference downloads do not establish app redistribution rights. Rights
for these four reference assets have not been confirmed. Confirm the rights or
replace the reference assets with licensed equivalents before distribution.

## Verification

- Four title identities checked against TMDB, including exact IMDb IDs.
- All four original PNGs visually inspected and verified to contain transparent
  pixels; sizes and SHA-256 hashes are in `kids-artwork-sources/asset-checks.json`.
- Ten changed Kotlin files parsed without syntax findings. This is a syntax
  check, not Kotlin type checking or an Android build.
- Focused JUnit cases added for identity/namespace matching, no needless
  lookups, concurrent deduplication, offline recovery, cancellation, and timeout.
  These tests were not executed because builds on this PC are prohibited.
- No Gradle command, compilation, APK build, installation, or on-TV validation
  was performed. The new APK must be built on another machine.
