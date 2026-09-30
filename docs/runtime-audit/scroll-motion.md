# Slow, continuous TV browsing motion

Movie/category rows, Home’s vertical viewport and focus-level fades, the search grid’s vertical movement and Details episodes share a critically damped spring. The common response uses the previous slower vertical-scroll stiffness (430 before the existing 1.6 duration scaling). Decorative durations retain their current scale. Movie/category/Home glides therefore become slightly more leisurely; search’s vertical pace is retained. The focus border keeps the previous search stiffness of 600. This change does not increase animation speed or the held-key cadence.

Search translates its border in a graphics layer at fractional pixel positions, removing rounded layout offsets. Physical taps are distinguished from Android held-key repeats, preserving deliberate input during the slow glide. Existing held-key pacing and section focus handoff remain.

Episodes continuously retarget from the running position/velocity instead of restarting or snapping when rapid taps put the target more than 2.5 episodes ahead. Only posters around the animated viewport are mounted, preventing outgoing cards from disappearing while the selected destination is ahead. A season change starts at its own current episode. The older Details entry delegates to the same episode implementation; callbacks, finite row edges and season navigation remain. Held Select cannot launch the same episode repeatedly.

Movie rows also retarget when the requested destination equals the current animated position. A newly started glide might still be headed elsewhere; skipping that retarget could leave the wrong expanded hero on screen after an immediate reversal.

## Checks

Eight added regressions verify unhurried/non-overshooting spring samples, position/velocity continuity, identical normalized horizontal/vertical responses, category tap/reversal selection, outgoing episode visibility during six rapid taps, immediate episode selection, season changes, legacy episode boundaries, repeated Select suppression and movie-hero reversal. Existing D-pad pacing, section handoff, viewport-window, preview scheduling and playback tests also run.

Debug APK and lint pass. Final suite: 173 tests, 169 passed, four opt-in live TMDB tests skipped, no failures/errors. Spring timing checks use the animation model and component checks use host Android/Compose; these are not physical-TV frame-rate measurements. The complete emulator/device audit remains tracked in `plan.md`.
