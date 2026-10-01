# Cloud Android development

Use the existing checkout; do not create a worktree during environment onboarding. The prepared cloud toolchain is Android SDK 35, build tools 34/35, full Temurin Java 21 and checksum-verified Gradle 8.9.

```bash
source /workspace/cloud-setup/env.sh
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Run the two repositories sequentially. Do not run Gradle and a software emulator together: this environment has four CPUs and no `/dev/kvm`. Cloud-only proxy, certificate authority, Maven mirror and Robolectric configuration live under `/workspace/cloud-setup`, outside Git. GitHub Actions validates debug builds, unit tests and lint and uploads APKs/reports for review.

Release builds require the original signing keystore and ignored signing properties. Never substitute the cloud debug key for the released app key. A blank `app/src/main/assets/update-gate.json` disables the retired update endpoint until a replacement HTTPS site is selected. Local update fixtures must override debug assets outside the checkout; do not commit local certificate keys or publish debug APKs through the release publisher.

TV validation: 156 tests executed, 152 passed and four opt-in live TMDB tests skipped; debug APK and lint passed (211 existing warnings remain). Six update-release publisher tests passed. Native Android host-rendered screenshots exercise update downloading, permission and installer-approval states. These are UI tests. A separate API28 software-emulator test completed a local HTTPS website update from versionCode 29092026 to 29092028 through Android installer approval, rejected an APK signed by a different key, and confirmed normal auth when no update was available. Runtime screenshots and fixture limitations are in `docs/emulator-update`. Final auth idle/focus checks are in `docs/emulator-auth`; sign-in remained visible beyond the 60-second screensaver timeout and the focused Space mark stayed readable.

The marketing site passed Chromium checks at desktop, tablet and phone widths, with no uncaught page errors or horizontal overflow. Its bundled release APK size and SHA-256 match its manifest. The existing bundled release predates the current app and does not prove that a future update will install.

Release verification still needs the original signing key, a deployed update URL, device playback/episode handoff checks, remote-control focus checks across auth/walkthrough/profile flows and timing measurements on real TV hardware. A passing debug build is not a production-readiness certification.

The later Android TV runtime baseline and explicit incomplete checks are in `docs/runtime-audit`; cold-play session promotion/expiry scheduling and acceptance criteria are in `docs/runtime-audit/warmup-plan.md`. Ten authenticated titles, including Mr. Robot, failed to render frames before those later pipeline corrections. New unit checks validate promotion/cancellation and ten-hour cache expiry, but live recovery still needs a working provider session.


The October 2026 review builds use the new NetflixPro signing key supplied locally in ignored app/release.jks and app/release-signing.properties. They require uninstalling the previous differently signed APK once. Future release builds must retain this same new key. Signing files are private and must never be committed to GitHub.
