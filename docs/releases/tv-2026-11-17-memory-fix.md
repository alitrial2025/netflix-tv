# TV 17.11.2026.2 — playback memory fix

- Version code: `29092057`; package: `com.netflixprotv.apk`; minimum SDK: `24`.
- Source: `bc56e7c27b1301f619de629e0b2ff9bb39558633`.
- Signed build and CI validation: https://github.com/alitrial2025/netflix-tv/actions/runs/44081941784 (debug, rules and release jobs passed).
- Local Android unit suite: 552 passed, 9 skipped. All 43 Node public-playback tests passed; lint and optimized test build passed. Production R8 suspend-response signature checks passed in CI.
- APK: https://npro-app.vercel.app/downloads/tv/29092057-7620b55124163b65.apk
- APK SHA-256: `7620b55124163b65fd800cf5695e1748b3ffd7e7bcd225b56a04b41008da76e8`; size: `7952816` bytes.
- Signing certificate SHA-256: `3cea049428d723e0fd69d7c9c00cdb35f08b3661468c1d4679a8ebefae034255`, identical to versions 29092055 and 29092056. Existing CI secrets supplied signing; no private release key was exported.
- Published at: `2026-11-17T16:22:21.576Z`.
- Vercel deployment: `dpl_9S6NjVFv3mhH6RnUmjT74mHfaEEW`; production alias: https://npro-app.vercel.app.
- Live manifest, APK hash/signature, unchanged mobile manifest/homepage and time API verified. All 85 other existing deployment source files were preserved, including archived APKs. Mobile remains version 44 (1.43).

This release replaces repeated Android native regex input copies with a single matcher cursor and reverts the compatibility track-selection/rendering changes. The Android parser fixture dropped from 527,703 KiB to 1,045 KiB native growth. Startup no longer waits for catalogue/artwork before profile selection, and TMDB HTTP caching and retained stage diagnostics are enabled.

The full signed-in emulator remained open after the fix but did not complete playback during the cloud resolution retest. A captured Flash S1E5 sample rendered video in the Android decoder harness. Live native resolver checks returned streams for S1E5, Stranger Things, Mulan and Game of Thrones; S1E4's issued source returned HTTP 403. The release does not certify that external provider failure is resolved; affected physical TVs still need a playback retest.
