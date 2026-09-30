# TV D-pad correction

Home now handles vertical section movement before dispatch reaches a row, using the latest requested position. Previously a second Down press could arrive while the previous row still held Android focus; that row calculated its destination from its own index, stalling progression. A delayed movie-focus callback could then restore the departing row.

The content viewport consumes vertical movement and section boundaries. It preserves adult Home’s billboard/categories/rows, other tabs’ billboard/rows, and the kids tabs without a billboard. A destination row still receives an immediate focus request and frame-based retries; a failed Boolean focus request no longer counts as success. Older retries cannot overwrite a newer direction. The existing remote repeat pacing and matched pixel/carousel springs remain.

A row that is no longer the logical destination immediately cancels optional artwork, logo/metadata and preview effects, even if Android still holds its focus node. Its horizontal/Select presses and delayed callbacks cannot overwrite the new request. The saved horizontal position remains available on return. Artwork remains limited to the viewport and adjacent targets; animation state stays in drawing/layout rather than driving every row’s composition.

## Verification

Nine new tests cover section traversal, reversal while focus attaches, empty/shrinking catalogs, adult/kids tab boundaries, real Compose key dispatch with deliberately retained child focus, and the actual `NetflixMovieRow` component. The carousel checks exercise rapid taps before the spring settles, left/right edges, immediate destination selection and inactive-row selection suppression/position restoration. Existing held-key pacing, viewport-window and anchor tests continue to run.

The final local suite has 165 tests: 161 passed, four opt-in live TMDB tests skipped, no failures/errors. Debug APK and lint passed. These are host Android/Compose regression checks, not a new authenticated emulator or physical-TV frame-rate benchmark. The earlier software-emulator D-pad audit timed out before completing row checks. Full-screen navigation, preview/first-frame timings and physical-TV smoothness remain unchecked in `plan.md`; no FPS or playback-speed claim is made here.
