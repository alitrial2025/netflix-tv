# Membership policy and security limits

All new purchases cost KSh 150 (Mobile), 550 (Basic), 950 (Standard), or 1,350 (Premium). Each payment adds exactly 30 × 24 hours in UTC. An early renewal of the same active plan adds 30 days after the existing expiry; a different plan starts a new 30-day period when payment is verified. The app warns before a plan change replaces remaining time. Existing paid expiry dates are retained.

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

Quality limits guide adaptive source selection. A single fixed-resolution source cannot be transcoded by these apps; its lowest available source remains a playback fallback. 4K, HDR and spatial audio require suitable provider tracks and device support. Screen counts and download-device counts in the legacy plan metadata are not protected by a central device/session registry; they must not be described as verified account-wide concurrency enforcement.

## Defenses in the app

- Known plan IDs, ACTIVE status and a future expiry are required. Expired accounts lose streaming and all paid extras; playback is stopped at expiry.
- A persisted monotonic clock prevents ordinary device-clock rollback from adding paid time. Current TLS-authenticated gateway time synchronizes that clock; Firestore server timestamps provide only a lower bound, because a subscription record may be old.
- Background video downloads reread the authenticated account's subscription using Firestore `Source.SERVER` before starting or resuming, including its quality and offline-title limits. Account changes and pending local writes cannot authorize a worker.
- Payment lookup validates the exact M-Pesa receipt, account/plan checkout reference, successful incoming KES transaction, sufficient whole-shilling amount, and valid server time. Withdrawals, refunds and reversals do not activate plans.
- Receipt consumption and membership updates remain one Firestore transaction with a globally unique receipt document. Retrying an already consumed receipt does not add another period, and a receipt already owned by another account/plan is rejected.

## What client-only code cannot prove

These defenses improve the genuine apps; they are not proof against a modified APK. Payment verification and membership writes still occur in the phone app. Merchant API authorization present in an APK can be extracted, and a modified client can skip validation if Firestore rules permit those writes. Local preferences and clock checkpoints are also editable on a compromised device. The repositories do not include the currently deployed Firestore rules, so production rule enforcement has not been verified or replaced.

No paid Cloud Functions were added, and no existing checkout was disabled. Strong protection of paid membership requires a trusted payment approval source and rules preventing clients from forging approvals or receipts. This can use a free trusted service or manual Firebase administration; it cannot be achieved by moving a secret between Kotlin, native code and encrypted assets. The existing exposed merchant credential needs rotation before a secure payment-service rollout; replacing it with another APK-embedded secret would repeat the exposure.

## Validation boundaries

Tests exercise plan pricing, all tier gates and expiry boundaries, exact 30-day periods across short/long/leap months, early renewal, monotonic clock rollback/restart behavior, gateway-evidence rejection, adaptive offline variant selection and retained separate audio. No live payment, merchant-account change, subscription activation, Firebase rule deployment or paid service provisioning is performed by these tests. Emulator playback remains deferred at the user's request.


Renewal timing: the paid expiry remains unchanged. Known paid ACTIVE/GRACE_PERIOD records receive at most 48 hours of renewal allowance after that expiry. The local reminder becomes due at +24 hours, and access ends at +48 hours even while the app stays open. Explicit suspended, expired, pending and unknown statuses grant no allowance. Same-plan renewal preserves only unexpired paid time, never the unused allowance.

A local WorkManager reminder verifies the signed-in account and current cloud expiry before notifying. Renewing or signing out cancels the scheduled notification. In-app reminders work without notification permission. Android scheduling, offline connectivity and device battery policies can delay a background notification; entitlement cutoff is independent of that notification.
