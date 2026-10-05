# Plan benefits and lightweight TV Home — 2026-10-05

## Plan followed

1. Map advertised benefits to actual enforcement in both apps and trace TV Home entry.
2. Correct benefit gaps and misleading plan details without weakening paid account/device checks.
3. Reduce work on inexpensive TVs while retaining the Home layout, artwork, animation and navigation.
4. Run Android unit/build/lint checks; record remaining physical-device release checks.

All four steps are complete for the available workspace checks. Physical-device release checks are explicitly listed below.

## Findings and changes

- Mobile and Basic previously denied approximately 38% and 18% of titles through an arbitrary title hash. Every active paid plan now includes the full available catalog. Existing prices, 30-day periods, renewal allowance, quality ceilings, supported devices, profiles, screens and paid extras remain. Guests, invalid memberships and expired accounts remain restricted; Mobile remains phone/tablet only. Plan details explain simultaneous screens and the single-device binding for Mobile/Basic.
- Phone login, membership refresh and receipt verification previously claimed the playback device and could reject billing on a phone when Basic was linked to a TV. Account confirmation now reads membership without claiming a binding or rejecting that billing session. Eligibility for playback is still checked against the linked device. First phone playback/download claims an unbound device; offline authorization is saved only after the device is actually confirmed. Existing immutable bindings and screen leases remain enforced. An existing Mobile/Basic link is retained across plan changes; moving that link still requires support. This change does not silently move existing users to another device.
- Phone download admission counted completed titles plus every task independently. Failed jobs consumed quota and a title could be counted twice during completion. Quotas now use a union of completed and queued/preparing/downloading/paused keys belonging to the current profile. Failed/completed task entries do not consume an extra slot. Retrying a failed task must fit the quota. Saved files are retained on downgrade. Worker reservations prevent two concurrent transfers consuming the last slot after a downgrade. Automatic downloads use the same counting policy; next-episode replacement checks capacity before removing the watched copy.
- Low-end TV detection now includes devices with up to 2 GiB physical RAM, which can report a 256+ MiB application heap and no low-RAM flag. Detection is cached, rather than querying the system per image/component.
- Optional Home work on these devices waits until Home has rendered and four seconds have elapsed since entry, plus the normal one-second input quiet period. Splash preparation, foreground playback and category input retain their separate paths. Focused optional previews wait six seconds on small TVs; Home billboard previews also wait for Home readiness. Ambient artwork sampling is deferred eight seconds on small TVs and still produces the same final tint.
- Small TVs prepare only the active tab. Switching tabs builds that tab off the main thread and caches it for return visits. Offscreen tab prebuilding remains available on larger TVs.
- Unfocused rows no longer obtain the preview controller, collect preview/membership state or attach lifecycle observers. Leaving the focused composition disposes its effects and stops the previous owner.
- Superseded profile personalization jobs are cancelled, with account/profile generation guards retained. Ranking uses consistent snapshots of taste/likes/community; cancelled row sorting checks cancellation between rows.
- Billboard selection uses a bounded heap/map of at most the requested number of candidates instead of fully sorting the catalog. Stable ties, duplicate-ID selection, release-date rules and Kids restrictions are checked against the original full-sort result.
- Startup warms only the actual adult Home hero, using the same URL, decode size, bitmap configuration, precision and scale as its visible request. Unrelated six-poster warmups and their extra bitmap variants were removed. Kids artwork keeps its own existing flow.

## Validation

TV: 299 tests, 290 passed and 9 opt-in tests skipped. Phone: 220 tests, 210 passed and 10 opt-in tests skipped. Both full `testDebugUnitTest`, `assembleDebug` and `lintDebug` runs passed with no lint errors. Existing lint warnings remain. Commands used Java 21 / Android SDK 35 / Gradle 8.9 with `--no-daemon --max-workers=2`, run sequentially. No actual low-end TV frame-time or real-account purchase/device-switch flow was measured here.

Logs: `/workspace/artifacts/tv-plan-home-validation.log` and `/workspace/artifacts/mobile-plan-home-validation.log`. Structured results: `/workspace/artifacts/plan-home-results.json`; unit XML archives: `/workspace/artifacts/tv-plan-home-unit-results/` and `/workspace/artifacts/mobile-plan-home-unit-results/`.

Added regressions cover billing without claiming a playback device, paid catalog/device restrictions, advertised screen counts versus lease policy, download deduplication/failed retries/paused jobs and concurrent last-slot reservations and safe retry after releasing a slot, low-end classification, entry/reentry quiet periods, and bounded ranking versus full stable sorting.

## Release checks

No physical low-end TV was connected for this change. Android build and unit checks do not measure actual input-to-frame latency, decoder pressure or jank on that hardware. Before rollout, compare the same device/build type with cold entry, warm reentry, held D-pad scrolling, quick tab changes and profile changes; check that hero/first-row art, focus and tint remain correct and that optional previews resume once idle. Verify Mobile/Basic/Standard/Premium on real accounts, including expiry, renewal, downgrade, full catalog playback and the download boundary. These checks do not require changing plan prices or making an extra charge during development.
