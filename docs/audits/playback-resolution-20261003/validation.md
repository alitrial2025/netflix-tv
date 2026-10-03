# Final validation — 3 October 2026

- App version: 3.10.2026.3 (29092037).
- Debug APK assembly, full unit suite and debug lint: successful.
- Unit tests: 304; failures/errors: 0/0; skipped: 9.
- Lint: zero errors; 243 warnings.
- Shared Kotlin public resolver/configuration/catalog code is identical across both apps.
- Node public-playback/CDN fixture suites: successful (TV 31, mobile 19 tests).
- JVM test workaround: external Gradle init script disables Robolectric Conscrypt in this JDK21 environment. No app TLS settings changed.
- Live Lanterns S1E1: HS public post and playlist returned the correct episode 1271684191; provider HLS returned the known rate-limit video. Mulan did likewise. Newly decoded cookie-free playback and physical-device startup timing remain unverified.

## Release signing

Release assembly stopped before packaging because the cloud checkout contains neither original app/release.jks nor app/release-signing.properties. A prior local check on 3 October at 11:34:11 UTC confirmed both files in D:\Netflixtv\app. They are ignored by Git and were not copied into this cloud checkout. No replacement signing key was generated, and no debug APK was published as an update.

The tracked release manifests for both apps expect certificate SHA-256 3cea049428d723e0fd69d7c9c00cdb35f08b3661468c1d4679a8ebefae034255. Recovered signing material must be verified against this certificate before generating update APKs for existing users.
