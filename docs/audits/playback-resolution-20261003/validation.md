# Final validation — 3 October 2026

- App version: 3.10.2026.3 (29092037).
- Debug APK assembly, full unit suite and debug lint: successful.
- Unit tests: 304; failures/errors: 0/0; skipped: 9.
- Lint: zero errors; 243 warnings.
- Shared Kotlin public resolver/configuration/catalog code is identical across both apps.
- Node public-playback/CDN fixture suites: successful (TV 31, mobile 19 tests).
- JVM test workaround: external Gradle init script disables Robolectric Conscrypt in this JDK21 environment. No app TLS settings changed.
- Live Lanterns S1E1: HS public post and playlist returned the correct episode 1271684191; provider HLS returned the known rate-limit video. Mulan did likewise. Newly decoded cookie-free playback and physical-device startup timing remain unverified.

## Signed release and publication

Both production GitHub Actions workflows passed debug checks, release assembly and release validation using the existing protected signing secrets. The original keystore did not need to be exported into this cloud workspace. Both APK certificates match the preceding published releases: SHA-256 `3cea049428d723e0fd69d7c9c00cdb35f08b3661468c1d4679a8ebefae034255`.

- TV build: [workflow 37142490958](https://github.com/alitrial2025/netflix-tv/actions/runs/37142490958), source `cd6d29d4e9219258ba9fd26620888279cb0695ef`, version 3.10.2026.3 (29092037).
- Mobile build: [workflow 37142499741](https://github.com/alitrial2025/netflix-mobile/actions/runs/37142499741), source `c7f7dfaed119eb3112f1855031330b7d995e2b8c`, version 1.11 (12).
- Publisher and website release-policy checks: 13 passed. Version increments, package names, non-debug status, signing continuity, update-gate configuration and immutable APK hashes were checked before deployment.
- Published both APKs and update manifests to [the existing website](https://npro-app.vercel.app/), Vercel deployment `dpl_AGbHnUuGS81NEcWjVYTTGWaPnmjD`. Prior APK files were retained.
- Live manifests match the staged releases, use no-store cache headers, and expose mandatory updates under the existing policy. Both publicly downloaded APKs match the manifest size and SHA-256. Download responses use the Android APK content type and immutable caching.
- The live streaming configuration matches the staged net52 configuration. The existing `netflixpro.vercel.app` alias exposes the same release manifests.

See `publication.json` for APK URLs, hashes verification and source build identities. Existing production installations can update with the same signing key. Newly decoded upstream playback and Android first-frame performance remain subject to the live limitation above.
