# Membership policy and security limits

All new purchases cost KSh 150 (Mobile), 550 (Basic), 950 (Standard), or 1,350 (Premium). Each payment adds exactly 30 × 24 hours in UTC. An early renewal of the same paid plan, including a GRACE_PERIOD-labelled record with unexpired paid time, adds 30 days after the existing expiry; a different plan starts a new 30-day period when payment is verified. The app warns before a plan change replaces remaining time. Existing paid expiry dates are retained.

| Benefit | Mobile | Basic | Standard | Premium |
|---|---|---|---|---|
| Adaptive video target | 480p | 720p | 1080p | 2160p |
| TV playback | No | Yes | Yes | Yes |
| Profiles | 1 | 2 | 4 | 5 |
| Offline titles per profile on this phone | 3 | 5 | 25 | 999 |
| Download Next Episode | No | No | Yes | Yes |
| Games on phone | No | No | Yes | Yes |
| Clips / Downloads for You / spatial audio | No | No | No | Yes |

Catalog selection on Mobile and Basic retains the existing title-lock policy. Standard and Premium retain full catalog access. Downloads for You queues only provider-supported titles. Premium retains its previous large offline library limit; no existing downloaded files are deleted on a downgrade. Existing excess titles must be removed before additional downloads can be started.

Quality limits guide adaptive source selection. A single fixed-resolution source cannot be transcoded by these apps; its lowest available source remains a playback fallback. 4K, HDR and spatial audio require suitable provider tracks and device support. Online entry leases enforce the declared screen count through fixed Firestore slots, renewed every 20 seconds and stale after 45 seconds. Mobile and Basic retain one registered device binding. Device identifiers are client-reported, without hardware attestation; download-device counts have no central registry and must not be advertised as enforced account-wide quotas.

## Defenses in the app

- Known plan IDs, ACTIVE status and a future expiry are required. Expired accounts lose streaming and all paid extras; playback is stopped at expiry.
- A persisted monotonic clock prevents ordinary device-clock rollback from adding paid time. Current TLS-authenticated time from the website synchronizes that clock; Firestore server timestamps provide only a lower bound, because a subscription record may be old.
- Background video downloads reread the authenticated account's subscription using Firestore `Source.SERVER` before starting or resuming, including its quality and offline-title limits. Account changes and pending local writes cannot authorize a worker.
- The trusted payment service validates the exact M-Pesa receipt, account/plan checkout reference, successful incoming KES transaction, sufficient whole-shilling amount, and valid server time. Withdrawals, refunds and reversals do not activate plans.
- The trusted payment service consumes receipts and commits all membership mirrors in one Firestore transaction with a globally unique receipt document. Retrying an already consumed receipt does not add another period, and a receipt already owned by another account/plan is rejected.

## Trusted payment approval

The new phone release posts its Firebase ID token, exact receipt, plan ID and persisted account/plan checkout reference to the existing website's `/api/payments/verify`. The Vercel service verifies the account token, looks up the merchant-authenticated PayHero evidence, checks the exact successful incoming KES payment and amount, and commits an immutable global receipt and all membership mirrors atomically using Firebase Admin. Fixed plan prices and expiry rules live on the server; client-provided prices or claimed payment success cannot approve a membership.

The accompanying Firestore rules deny client writes to `used_receipts`, `users/{uid}/subscription/current`, `subscriptions/{uid}`, and paid-plan flags in `users/{uid}`. Ordinary account-owned data remains writable, and new accounts can initialize Guest/NONE fields. The app reads the committed subscription back from Firestore before granting local membership. Authenticated Guest is distinct from signed out: My Netflix shows View Plans, and downloads present an upgrade choice before any authentication navigation.

Deployment requires protected Vercel `FIREBASE_SERVICE_ACCOUNT_JSON` for the existing project and `PAYHERO_API_AUTH`, followed by publishing the matching Firestore rules. The new APK must not be released against an unconfigured service. Old APKs contain the previous merchant credential; that credential should be rotated through the protected server configuration. The new APK no longer embeds it. Local preferences remain editable, but they cannot create a paid server entitlement.

## Validation boundaries

Tests exercise plan pricing, all tier gates and expiry boundaries, exact 30-day periods across short/long/leap months, early renewal, monotonic clock rollback/restart behavior, gateway-evidence rejection, adaptive offline variant selection and retained separate audio. No live payment, merchant-account change, subscription activation, Firebase rule deployment or paid service provisioning is performed by these tests. Emulator playback remains deferred at the user's request.


Renewal timing: the paid expiry remains unchanged. Known paid ACTIVE/GRACE_PERIOD records receive at most 48 hours of renewal allowance after that expiry. The local reminder becomes due at +24 hours, and access ends at +48 hours even while the app stays open. Explicit suspended, expired, pending and unknown statuses grant no allowance. Same-plan renewal preserves only unexpired paid time, never the unused allowance.

A local WorkManager reminder verifies the signed-in account and current cloud expiry before notifying. Renewing or signing out cancels the scheduled notification. In-app reminders work without notification permission. Android scheduling, offline connectivity and device battery policies can delay a background notification; entitlement cutoff is independent of that notification.

## Audit corrections — 2026-10-18

- Trusted time ignores later wall-clock edits. A boot-bound elapsed checkpoint survives rapid process restarts; a new boot requires fresh online time to restore a trusted anchor. Time synchronization is optional and bounded, and cannot revoke an admitted offline session merely because transport fails.
- Merchant-confirmed receipts can recover their original checkout nonce after reinstall or device changes only when the original reference proves the same account and plan. Receipt consumption remains globally atomic and idempotent.
- Suspended memberships cannot self-reactivate through a new payment. Payment verification rejects conflicting merchant evidence and uses a private, transactional five-attempt/minute budget with a five-second gap. An already applied receipt bypasses gateway throttling and never adds another period.
- Cloud profile creation goes through `/api/profiles/sync`, which counts existing profiles and serializes concurrent creations through a parent-document transaction. Existing profiles remain editable after a downgrade; direct client creates are denied by the matching rules. Both clients and the TV outbox use this route.
- The mobile sheet retains the code and checkout reference until its committed membership is confirmed. It distinguishes renewal due, expired, suspended and pending states, and blocks another checkout while access refresh is pending. TV shows the actual current membership status in upgrade prompts.

Deployment order: publish the new server endpoints and mandatory app updates before applying the matching Firestore rules. Vercel authentication and Firebase rule-deployment authentication are separate.

Refund operations: incoming refunds/reversals are rejected during redemption. No signed refund webhook is configured. Refunding an already activated payment requires support to set the canonical subscription to SUSPENDED (and update the reporting mirror); an old receipt cannot undo that suspension. Offline playback cannot learn of a new server revocation until reconnecting. These are operational limits, not guarantees against modified clients or device-root access.
