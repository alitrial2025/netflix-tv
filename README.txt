NetflixPro desktop review package — 1 October 2026

Extract the downloaded ZIP into a folder on your desktop.

NetflixPro-TV-debug.apk: Android TV review build, version 1.10.2026.2 (version code 29092029).
NetflixPro-Mobile-debug.apk: Android mobile review build, version 1.4 (version code 5).
Both apps require Android 7.0 (API 24) or newer. Install the matching APK on your Android TV/phone, not directly on Windows. These are debug-signed testing builds. A previously installed app signed with another key will reject an in-place update; retain your data before changing installations.

Mobile: lower Home scroll work without changing scroll speed, provider-verified Smart Downloads, separate-audio local HLS bundles, preserved warm sessions on CDN renewal and clearer download/playback errors. Full suite: 85 passed, 10 optional film exports skipped; Home regressions, assembly, lint and offline audio/video decode passed.
TV: preserve valid sessions on CDN errors and Retry, independent CDN/provider queues, dynamic master/audio signatures, Smallville episode/OTT route parity. Kotlin suite: 183 passed, 9 skipped; Node fixtures, assembly and lint passed.

NetflixPro-Cinematic-1080p.mp4: completed 3-minute film with stereo sound; open in your desktop media player.

Source reviews:
https://github.com/alitrial2025/netflix-tv/pull/1
https://github.com/alitrial2025/netflix-mobile/pull/1

Both APK signatures passed. Phone/TV hardware frame timing, live Android provider playback and production release signing remain unverified. Offline decoder proof uses locally generated fixtures. Native UI uses staged catalogue/account fixtures in the film.

SHA256SUMS.txt contains checksums for all three files.
