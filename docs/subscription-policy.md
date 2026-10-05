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

All active paid plans include the full available catalog. Quality, supported devices, screen limits and extras distinguish tiers; title hashes do not deny paid catalog access. Downloads for You queues only provider-supported titles. Premium retains its previous large offline library limit; no existing downloaded files are deleted on a downgrade. Existing excess titles must be removed before additional downloads can be started.

Quality limits guide adaptive source selection. A single fixed-resolution source cannot be transcoded by these apps; its lowest available source remains a playback fallback. 4K, HDR and spatial audio require suitable provider tracks and device support. Concurrent streaming screens are enforced through the Firestore session registry (1/1/2/4). Mobile and Basic bind to one playback device; Basic supports either a TV or a phone/tablet on that device. Phone account management and receipt verification do not claim a playback binding and remain available when Basic is linked to a TV. The phone claims an unbound device on first playback or download; TV login retains its device confirmation. Standard and Premium allow multiple devices within their simultaneous-screen limit. Download-device counts in legacy metadata have no central enforcement and are not advertised as verified account-wide limits.

## Defenses in the app

- Known plan IDs, ACTIVE status and a future expiry are required. Expired accounts lose streaming and all paid extras; playback is stopped at expiry.
- A persisted monotonic clock prevents ordinary device-clock rollback from adding paid time. Current authenticated payment-server time synchronizes that clock and remains authoritative during the process even when the device clock is fast; Firestore server timestamps provide only a lower bound, because a subscription record may be old.
- Background video downloads reread the authenticated account's subscription using Firestore `Source.SERVER` before starting or resuming, including its quality and offline-title limits. Account changes and pending local writes cannot authorize a worker.
- Payment lookup validates the exact M-Pesa receipt, account/plan checkout reference, successful incoming KES transaction, sufficient whole-shilling amount, and valid server time. Withdrawals, refunds and reversals do not activate plans.
- Receipt consumption and membership updates remain one Firestore transaction with a globally unique receipt document. Retrying an already consumed receipt does not add another period, and a receipt already owned by another account/plan is rejected.

## Trusted payment service and local limits

Payment verification and membership writes now run in the Cloudflare Worker stored under the phone repository's `payment-server/`. Both apps use read-only canonical memberships. Merchant authorization and the dedicated Firebase service-account key are Cloudflare secret bindings. Root membership mirrors, canonical subscription records, receipt consumption and verification locks deny client writes in the supplied rules. The actual production rules were inspected during this change and already deny client membership writes; the deployed rules now match the prepared source and keep receipt access server-only.

The phone checks authenticated billing availability before enabling checkout. Missing backend configuration or unavailable verification prevents taking a new payment. It preserves the pending account/plan reference across process restarts so an interrupted response can retry the same receipt. Verification is atomic and idempotent; a retry never extends membership twice. Account changes clear payment form state and block a pending result from activating a different account.

Local preferences, device confirmations and clock checkpoints remain editable on a compromised device. An offline phone can use only its previously server-confirmed matching account/device/entitlement until its normal expiry/renewal allowance; remote revocation is applied when it reconnects. A correct server clock fixes a fast device clock in the running process; a cold offline launch cannot establish fresh server time. Do not describe offline access as a fresh cloud authorization.

## Validation boundaries

Both Android build/unit/lint suites and server policy/JWT/concurrency tests are exercised. Firestore emulator checks cover receipt races, atomic mirrors, early renewal and denial of forged client memberships. Live read-only checks verify merchant connectivity, receipt lookup, service-account permissions and deployed authentication rejection. No real payment or paid-membership activation was performed. A controlled checkout and phone/TV entitlement refresh remain release checks. See `reliability-plan-2026-10-05.md` and the phone repository's `payment-server/README.md` for results and reproduction.

Renewal timing: the paid expiry remains unchanged. Known paid ACTIVE/GRACE_PERIOD records receive at most 48 hours of renewal allowance after that expiry. The local reminder becomes due at +24 hours, and access ends at +48 hours even while the app stays open. Explicit suspended, expired, pending and unknown statuses grant no allowance. Same-plan renewal preserves only unexpired paid time, never the unused allowance.

A local WorkManager reminder verifies the signed-in account and current cloud expiry before notifying. Renewing or signing out cancels the scheduled notification. In-app reminders work without notification permission. Android scheduling, offline connectivity and device battery policies can delay a background notification; entitlement cutoff is independent of that notification.
