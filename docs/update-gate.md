# Update Gate

Both apps check **https://netfixpro.netlify.app** on launch. This uses static HTTPS files; no Firebase rules, Cloud Functions or separate backend are required. Guests receive updates too.

## First release

1. Build the updated TV and phone sources yourself. No Android build or install was run during this update.
2. Keep the original release keystores. The TV source is now version **29.9.2026.1 / 29092027**; mobile is **1.2 / 3**.
3. Run the publisher below on each signed **standalone APK**. It inspects the existing APK; it does not build anything.
4. Deploy the entire `D:\Netflixtv\marketing-site` folder to your existing Netlify site. For a Git deployment, set the base directory to `marketing-site`, publish directory to `.`, and leave the build command blank. Manual folder uploads use the included `_headers` file.
5. Users of older apps install this first gate-enabled version manually once. Later releases download in the app.

The website currently includes your existing TV APK **28.9.2026**, explicitly labelled as a legacy release. It does not contain Update Gate or these latest source edits. There is no built mobile APK available, so the mobile download is marked coming soon. The publisher replaces these labels and links when you publish the new APKs.

## Publish an already-built release

Run from `D:\Netflixtv` in PowerShell. Replace the APK path with the actual file you built:

```powershell
node .\tools\update-gate\publish-update.mjs --channel tv --apk "D:\path\to\your-new-tv-release.apk"
node .\tools\update-gate\publish-update.mjs --channel mobile --apk "D:\path\to\your-new-phone-release.apk"
```

If Node is not on PATH, use this installed executable instead of `node`:

```powershell
& "C:\Users\mzazimhenga\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe" .\tools\update-gate\publish-update.mjs --channel tv --apk "D:\path\to\your-new-tv-release.apk"
```

Optional arguments:

- `--notes-file "D:\path\to\release-notes.txt"`: short user-facing notes, up to 2,000 characters.
- `--dry-run`: inspect and preview without changing files.
- `--sdk "D:\Android\Sdk"` or `--java "D:\jdk\bin\java.exe"`: specify read-only APK inspection tools if auto-discovery cannot find them.
- `--apk-url "https://your-file-host/path/release.apk"`: put the APK on another HTTPS file host and publish only its metadata to Netlify. Upload the exact APK to that URL **before** deploying the manifest.

The publisher verifies the APK signature using Android's `apksigner`, reads its package/version/minimum SDK, calculates SHA-256, checks the signing certificate against the previous release, copies the APK to an immutable filename and writes `updates/tv.json` or `updates/mobile.json` last. It refuses the wrong package, debug signatures and a version code that has not increased. Only use `--allow-legacy` when intentionally publishing an older APK without Update Gate.

**For every later release:** increase that app's `versionCode`, build with the same key, run the publisher and deploy the whole website. Existing downloads need their old immutable APK URLs to remain available while they finish. There is no periodic background forced installation: users receive the check on their next app launch.

## Netlify file hosting

Your existing TV APK is **24,244,116 bytes (23.1 MiB)**. Netlify says files above 10 MB are not well supported and can cause a deployment to fail. The included local-download layout lets you try a website-only deployment; the configurable `--apk-url` option supports another HTTPS file host if Netlify rejects the APK. See [Netlify's large-file guidance](https://docs.netlify.com/build/configure-builds/troubleshooting-tips/#large-files-or-sites).

No live Netlify deployment or external upload was performed. The provided public address returned HTTP 404 during the check; confirm that your Netlify site's name is exactly `netfixpro` and deploy the website folder there. The apps are configured with the exact URL you supplied.

## In-app behavior

- One bounded launch check; offline or invalid metadata allows normal app startup after at most 3.5 seconds. A newer compatible release opens the black Update Gate screen and starts downloading automatically.
- Android DownloadManager keeps downloading across process exit. **Later** lets the user keep using the app; installation waits for a later launch. Network pauses show their status and can resume.
- Downloaded bytes are copied to internal app storage. The app verifies size, SHA-256, package name, version, minimum SDK, a standalone APK and the exact signing certificate of the installed app before staging installation. Android verifies the package as well. A different signing key is rejected; key rotation is not supported by this first implementation.
- On Android 8+, users may need to allow NetflixPro to install updates once in system settings. On Android 12+, the installer requests unattended self-update when Android allows it and handles a system confirmation when required. See [Android's self-update requirements](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
- Android replaces the process during installation. The app requests reopening after replacement, but some devices restrict that; the user can reopen it from the launcher. See [Android's activity launch restrictions](https://developer.android.com/guide/components/activities/secure-bal).
- Account data is preserved because this replaces the same package without uninstalling or clearing its data. Update state is excluded from mobile backup/device transfer to avoid restoring download/session IDs on another phone.
- There is no remote Kotlin/Compose hot replacement. UI changes arrive in the signed APK; a Firestore document alone cannot install new native UI code.

If your permanent domain changes, configure it **before** building a new gate-enabled release:

```powershell
node .\tools\update-gate\configure-update-gate.mjs https://netfixpro.netlify.app
```

Keep the old release endpoints working while distributing a domain change, because existing apps use the URL baked into their installed APK.

## Verification

44 Node checks passed for release validation, version/signing guards, APK config reading, website metadata handling and gate source policies. Five actual local HTTP checks passed, including the APK link and exact byte size. The existing TV APK was actually inspected and copied with a matching hash. The desktop browser helper failed, so the website was reviewed using bundled Playwright with an isolated headless Microsoft Edge. Seven browser checks passed for desktop/phone/tablet layouts, actual APK download, the PIN/Update Gate tour, keyboard navigation and no uncaught JavaScript errors. Kotlin/XML checks are source-only; no Android compilation, install, installer permission flow, restart or device playback test was run.
