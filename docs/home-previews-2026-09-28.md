# Home focus and DirectCDN previews

## Behavior

- Hero details reveal while Play or More Info has focus and collapse smoothly
  when focus leaves. The compact action row stays anchored at the bottom.
- Resting on Play or an expanded row card requests a content preview after
  2.8 seconds and a quiet input/scroll window. Pressing Play still opens full
  playback; focus alone starts the optional preview.
- A film chooses a random whole minute with room for 60 seconds. A series
  chooses a valid season and episode from its episode list, then a random
  minute in that episode. Returning to a title reuses its choice.
- A preview runs for 60 seconds of media time, or until the source ends if it
  is shorter. It runs once per focus stay. Its progress never changes Continue
  Watching or Android TV Play Next.
- The artwork covers startup/buffering until the first preview frame. Video
  fades in; in-card labels, badges and progress disappear beneath it. The row
  heading, description below the card and white selection ring stay visible.
- English subtitle cues, when the source provides them, appear at bottom-left.
  Hero captions sit above the action row. Row previews are muted.

## Resource and request policy

- Home and category rows share one lazy preview ExoPlayer. Pending work is
  cancelled and joined before a replacement starts; there is no request queue.
- Focus changes stop decoding and clear media buffers. Navigation to full
  playback, lifecycle pause, profile changes and memory pressure release it.
  An unused instance also releases after 15 seconds.
- New resolutions are spaced by at least 10 seconds across all surfaces;
  usable cached streams can be reused after focus settles.
- HTTP calls within an optional preview lookup are also spaced by one second.
  Foreground playback does not wait for this optional-preview timer.
- HTTP 429 pauses all previews, including cached-source playback, for at least
  60 seconds. Repeated limits increase the wait up to five minutes. A longer
  Retry-After value is respected. Neither the resolver nor preview player
  immediately retries a rate-limited request.
- Muted row resolution skips audio master construction and audio probes. It
  uses existing verified subtitle metadata or one optional playlist lookup.
  Lightweight preview streams have separate cache identities from full playback.
- Buffers are capped at 2 MB on devices classified as low-memory and 4 MB
  otherwise, with no back buffer. Track preferences favor lower resolution,
  H.264 and at most 30 fps. Actual resolution depends on the CDN's variants.
- Stream result caches are bounded to 32 entries, and random preview choices
  to 24. Episode-page lookup stops once the requested episode is found. A
  missing episode fails instead of launching a different episode.

## Verification status

No Gradle tasks, Kotlin compilation or APK builds ran on this PC. New preview
files and changed resolver, Home and navigation files received source syntax
checks and API review against the project's Media3 1.2.1. Player and Details
job/cookie holders now use named classes, which also parse cleanly. Source
syntax checks cannot establish compilation or validate generated DEX.

JUnit cases were added for request spacing, rate-limit backoff, Retry-After
parsing, random minute bounds and reuse. They have not been executed here.

The next release built on another machine should be checked on the TV for:
rapid horizontal/vertical repeats; Play/More Info focus transitions; a random
film and TV minute; subtitle placement; a full minute without preview looping;
leaving and returning after backgrounding; full-playback resume unaffected by
preview; rate-limit response pacing; and memory/frames during a long browse.

The supplied photographs guided Home geometry, but no post-change runtime
screenshot or 60 fps/500 MB device measurement is available. Kids character
cutout support is present; genuine title-specific PNG/WebP assets still need
to be placed in `app/src/main/assets/kids_characters`.

The installed-APK Home VerifyError and source refactor are documented in
`home-entry-fix-2026-09-28.md`; Details to Player changes are in
`details-player-handoff-2026-09-28.md`.
