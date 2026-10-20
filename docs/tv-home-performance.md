# TV Home rendering and navigation

The 20.10.2026.1 update reduces work on the TV Home screen without changing its dimensions, artwork requests, motion durations, easing, selection ring or shared-player architecture.

- Home passes its measured viewport width to each visible rail. These rails use an ordinary Box instead of a separate BoxWithConstraints subcomposition. Standalone rails continue to measure their own constraints.
- Static posters keep their overlay opacity without creating the two preview fade animations. Expanded cards retain both existing fades.
- Preview cleanup stops and clears a player only when necessary, and only while the preview still owns it. Repeated navigation while idle does not create more idle-release jobs.
- Tab reveals and vertical scrolling independently block optional background work. Finishing one motion does not unblock the other. Catalogue refreshes and optional ranking results wait for quiet input before updating an existing screen; initial catalogue/active-row loading remains available immediately.

## Verification

MovieRowRenderingTest compares every rendered pixel of the measured and reused-width rails at 640, 960 and 1280 dp. The offline fixture covers card geometry, clipping, rank numbers, callout/reminder badges and progress bars. A native Compose remote-dispatch test selects the correct destination during rapid horizontal motion and after reversing direction.

HomeStartupSchedulerTest covers overlapping tab/scroll motion and leaving Home during a reveal. HomePreviewPlayerCleanupTest checks repeated cleanup, queued idle sources, buffering and ownership transfer. Existing motion, bounded-window, shared-session and D-pad tests remain enabled.

Run focused checks with:

```sh
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest \
  --tests '*MovieRowRenderingTest' --tests '*MovieRowWindowTest' \
  --tests '*HomeStartupSchedulerTest' --tests '*HomePreviewPlayerCleanupTest' \
  --tests '*SharedPlaybackSessionTest' --tests '*HomeDpadDispatchTest' \
  --tests '*HomeMotionTest' --tests '*TvMotionTest'
```

Rendering comparisons write generated images under app/build/reports/tv-home-review. Robolectric verifies rendering and behavior; it does not measure frame rates on physical TVs. Device-level improvement should be assessed on the affected hardware using Android frame timing tools.
