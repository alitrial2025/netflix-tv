# Mandatory app updates

Both production apps use `https://npro-app.vercel.app/updates/{tv,mobile}.json`. Website download cards read those same manifests. Static Vercel files are sufficient; no payment or update server was introduced.

The app checks before opening its screens, on return to the foreground, and every 15 minutes while open. Background refreshes keep the current screen visible during the check. Every newer release is mandatory: the gate downloads automatically and offers installation permission, Android confirmation, Retry or Exit app. Back exits the activity; neither Back nor Later can expose app content. Failed metadata requires retry, and a cached required release survives offline checks and manifest rollback. Installation status alone cannot unlock the old version: the installed package version is checked again.

The startup check is bounded to eight seconds, after which an unsuccessful check shows Retry. DownloadManager resumes downloads across process exits. Installation verifies size, SHA-256, package, version, minimum Android version and the installed signing certificate from internal storage. Android may require one-time permission to install updates and installation confirmation. Same-key updates preserve account data. Devices on an unsupported Android version remain blocked with an explanation.

## Publishing

1. Increase versionCode and keep the production signing key unchanged.
2. Push the changes and wait for GitHub Actions signed release, unit tests, lint and APK checks. Build in GitHub Actions, not locally.
3. Download and verify the signed standalone APK.
4. From this repository, run the publisher for both channels:

```sh
node tools/update-gate/publish-update.mjs --channel mobile --apk /path/mobile-release.apk --notes-file /path/notes.txt
node tools/update-gate/publish-update.mjs --channel tv --apk /path/tv-release.apk --notes-file /path/notes.txt
node --test tools/update-gate/release-lib.test.mjs marketing-site/tests/release-policy.test.mjs
```

5. From `marketing-site`, deploy its linked project with `vercel deploy --prod --yes --scope alitrial2025-9479`.
6. Verify both live manifests, immutable same-origin APK URLs and downloaded byte hashes. Commit the updated manifests.

Old immutable filenames must remain deployed while an in-progress download finishes. Manifests use no-store; versioned APKs use immutable caching. APK files are deployed to Vercel and excluded from new Git source commits.

The publisher rejects debug signatures, wrong packages, non-increasing versions and production signing-key changes. `--replace-debug-baseline` explicitly migrates only a previous manifest marked `buildType: debug`; it cannot change an existing production key. Debug installations cannot accept a production APK as an Android update and need a fresh installation once, which may remove local account data. APKs with a blank update URL need a one-time manual installation; a website manifest cannot modify an installed APK.

## Playback startup

The 10–15 second target is a performance goal, not a failure deadline. Slower lookups continue and show “Still finding your video…” after 15 seconds. Repeated device confirmation during playback was removed because the live screen-slot transaction already validates subscription, bound device and screen count. TV verifies cold-start access once. Mobile uses 900 ms initial buffering and 1,500 ms after a stall. Provider identity, season, episode and signed URL validation remain enabled. Genuine network failures and existing overall lookup limits still produce errors.
