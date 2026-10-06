# Published updates — 4 October 2026

Mobile **1.13 (14)** and TV **4.10.2026.1 (29092039)** are live at https://npro-app.vercel.app/. Both protected production signing workflows completed successfully. The original production certificate matches both APKs, so installed release apps can update in place. Production APKs are not debuggable and do not contain the merchant authorization credential. Compiled DEX differs from the preceding release, and mobile contains the trusted payment endpoint.

Live update JSON exactly matches staged manifests, downloaded APK bytes match their SHA-256 and size, and manifests/APKs retain no-store/immutable caching respectively. Existing APK URLs, the previous website alias and the streaming runtime configuration remain available. Evidence and CI source commits are recorded in `publication.json`, `apk-verification.json` and `source-builds.json`.

Validation: TV 325 unit tests and mobile 244 unit tests pass with zero errors/failures; 9/10 existing device-dependent tests are skipped. Debug and protected release builds/lint pass. There are no debug lint errors; existing warnings are listed in `validation.json`. Shared payment and checkout tests: 14; Firestore emulator tests: 18; publisher/policy tests: 13.

Episodes S1E1→E2→E3 use ten provider HTTP requests in the regression fixture, with zero duplicate Media3 startup-manifest fetches. Optional previews use a 15-second internal startup budget, short focus debounce, lighter quality/buffering and cancellation when hidden; no visible timing clock was added. Actual first-frame timing on customer devices remains unmeasured. Provider cooldown protection remains enforced.

The payment service and server-only database rules are live. Copied and fabricated receipt checks produced no membership writes and no new payment was made. Successful activation using a customer's existing account-bound checkout receipt still needs confirmation in the updated app; customers should retry that receipt rather than pay again.
