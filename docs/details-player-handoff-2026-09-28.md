# Details preview to full playback

## Changes

- Details and Player share one prepared full-playback player and source.
  Screen ownership and `PlayerView.switchTargetView` prevent an outgoing
  screen from stopping or stealing its successor's video output.
- Full playback restores the viewer's original resume position, including
  an explicit Android TV Play Next position. Time spent viewing the Details
  preview does not advance saved progress. A different episode cannot inherit
  the old preview's position.
- Posters stay until a frame renders. Full playback fades in from its first
  frame. Details begins optional work after the entrance and an input-idle
  window; its actions stay visible when preview playback is unavailable.
- Expired prepared full streams resolve afresh after standby. Details and
  Player recover at most once from an authentication failure automatically.
- Trailer playback is explicit and has a separate media ID. Failed full
  playback shows Retry instead of switching to a trailer. Trailers never
  publish Continue Watching or Play Next progress.
- A shared provider cooldown applies to warmup, preview resolution, Details
  and Player. HTTP 429 observes Retry-After and does not renew authentication
  or immediately send another request. Raw-video fallback preserves position
  and uses the same title.
- Full playback has a 60-second initial-frame timeout; stream resolution
  remains bounded to 58 seconds. The timeout ends stalled work and exposes
  Retry; successful loading time depends on the provider and TV.
- Full playback buffers target 8 MB on low-memory devices and 16 MB otherwise,
  with no back buffer. Fullscreen artwork downloads and decodes have bounded
  dimensions, and title logos retain their alpha channel.
- Missing episode lists expose Retry instead of invented episodes. Next-episode
  preloading checks that the episode exists. Details subtitle selection uses
  the same exact option matching as Player.

## Verification status

Source syntax and the project's Media3 1.2.1 APIs were reviewed. JUnit cases
were added for screen ownership, resume isolation and provider cooldown.
No Gradle tasks, Kotlin compilation, APK builds or JUnit execution ran on
this PC. A new APK needs device checks for immediate/repeated Play presses,
episode and season changes, Back during loading, resume/deep-link positions,
standby with expired URLs, captions Off/English, and rate-limit recovery.

The detected installed-APK Home entry crash is tracked separately in
`home-entry-fix-2026-09-28.md`.
