# Lioness S1E1 diagnosis

The user reported Lioness S1E1 failing with the generic playback error while Lanterns plays on their phone. A Lioness-only audit of the released production Kotlin resolver reproduced `No verified public provider identity is available for this title` before any episode or media request. See `lioness-before-native.json`.

The cookie-free provider search publishes Special Ops: Lioness (2023) under Prime ID `0LEE086T9L711TRMJ0ODBQHZGS`. The app initially searches the TMDB title Lioness; authoritative TMDB aliases arrive later, but the original search records were discarded. The fallback public Prime search also emits entityType `TV Show`, which the previous parser did not accept.

The public Prime detail for that exact provider ID identifies Lioness Season 1 (2023), with explicit seasonNumber 1. Its season selector links to another regional edition, `0SVGUHKPBBP0BH7FC5VO19ALDR`. The provider returns no episodes for that linked ID. Keeping the actual verified detail URL ID returns eight explicitly labelled S1 episodes, including E1 Sacrificial Soldiers (`0KVBLO9DOW99VWB40BUV66DI2U`). These IDs were observed in public responses; they are not guessed or newly hardcoded production seeds.

The shared correction rechecks already-fetched provider search records after authoritative aliases arrive, accepts typed public Prime TV Show cards, and preserves the verified current Prime detail ID when an explicit consistent seasonNumber identifies its season. All candidate validation and wrong-season checks remain in place. Regression fixtures cover the full alias-to-exact-episode flow and reject conflicting season headers, unrelated shows, wrong release years/types and untrusted links.

The corrected production resolver now selects provider season ID `0LEE086T9L711TRMJ0ODBQHZGS` and requests the playlist for exact S1E1 `0KVBLO9DOW99VWB40BUV66DI2U`. This took approximately 5.9 seconds in the local audit. The media response was classified as upstream rate-limited, so no new video segment or on-device playback is verified from this workspace. See `lioness-after-native.json`. The original failure occurred before the episode lookup; this independent upstream limitation must not be conflated with the corrected identity bugs.

Focused TV regression checks: 25 passed. The mobile checks and production signed builds are tracked separately before publication. Release versions for this correction: TV 3.10.2026.4 (29092038), mobile 1.12 (13).
