# Discovery Firestore validation

This folder validates the deployable rules in the repository root using an isolated demo project.

Requirements: Node 20+ (tested with Node 24), Java 21+, and available port 8787. Dependencies are pinned in `package-lock.json`. From this folder:

```bash
npm ci
npm test
```

The test command starts only the Firestore emulator using `demo-netflixpro-discovery`, runs nine tests, and stops it. Expected denial logs are part of negative tests. No Google service credentials are required. Do not change the test project argument to a real project.

The local fixture `firestore.rules` must match `../../firestore.rules` byte-for-byte, checked before the tests. The root file merges the existing rules supplied by the user with bounded daily discovery counts and private profile reminders. Tests also check that existing profile/history/subscription ownership and TV pairing permissions still work, while recursive grants cannot bypass the new vote/quota/reminder restrictions.

Production deployment is still pending Firebase administrator authentication or publishing the root rule file in Firebase Console. Client `google-services.json` does not provide that administrative permission. Deploy once from either repository after authentication; both apps share the configured Firebase project. Do not deploy the emulator configuration.

Tests establish deduplication, quotas, migration races and access boundaries. They do not prove viewing time from an untrusted modified app. Existing subscription-write and public pairing policies were supplied by the user and preserved; they are not assessed as payment authorization in this change.

Details and collection fields: `../../docs/live-discovery-and-recommendations.md`.
