# Runtime and UI audit plan

- [x] Confirm both previous PR heads pass GitHub build/test/lint; compare supplied Firebase configuration without exposing values.
- [x] Prepare a real Android TV AVD; check supported live-view capability.
- [x] Sign in with the supplied test account; confirm membership and catalog availability.
- [ ] Capture cold and warm startup, splash, auth/keyboard, walkthrough/profile setup, profile picker/edit/avatar/PIN, Home tabs, search/categories, details, player controls/audio/subtitles/settings and return navigation.
- [ ] Record billboard and movie-card preview eligibility, resolution, preparation, first visible frame and cancellation while moving focus.
- [ ] Attempt five distinct movies and five distinct TV shows; record resolution and first-frame durations, errors, playback stability, return/resume behavior and one next-episode handoff.
- [ ] Exercise short taps, held/repeated D-pad keys, row boundaries, horizontal movement, vertical section movement, focus return and input during playback/overlays.
- [ ] Capture frame statistics and identify measured hotspots; implement focused fixes and thinner search/category/movie-row focus rings.
- [ ] Align mobile Home category height/corners/tint/spacing, header-to-hero spacing, poster-derived background and hero/footer actions with the reference.
- [ ] Repeat affected tests on the same emulator configuration; compare timings and screenshots, and capture a separate walkthrough recording if supported.
- [ ] Run relevant builds/tests/lint, update PRs and document production/device/network limitations.

Timings will use monotonic device clocks where available. Activity first draw, route visibility, resolver completion and first rendered video frame are different measurements. Failures/timeouts remain failures; substitute footage does not count as stream success. Software-emulator timings support comparisons on this configuration, not claims about physical TV performance. Raw credentials, tokens, full signed URLs and account-specific logs stay out of reports and Git.
