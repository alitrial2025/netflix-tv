# Payment, subscription and plan audit

## Confirmed defects corrected

| Area | Failure | Correction |
| --- | --- | --- |
| Trusted time | A future device date overrode a server correction; short restarts lost elapsed time | Ignore wall time after synchronization and restore an elapsed anchor only within the same boot |
| Checkout recovery | Reinstall lost the local checkout nonce and stranded a valid payment | Recover the merchant reference only when it proves the same authenticated account and plan |
| Renewal | A GRACE_PERIOD label discarded future paid time | Preserve unexpired same-plan paid time; never roll unused grace into a new period |
| Suspension | Another payment reactivated a suspended membership | Block activation and another checkout; retain the receipt for support |
| Evidence | Conflicting reference/status aliases and outgoing direction could pass partial checks | Validate supplied evidence consistently and reject malformed amounts |
| Verification abuse | Repeated authenticated attempts could repeatedly query the merchant | Private transactional verification budget; exact applied-receipt recovery remains available |
| Cloud profiles | App-only count checks allowed concurrent/external extra profiles | Trusted account transaction counts profiles, serializes creates, preserves existing profiles after downgrade |
| Payment UI | Receipt cleared and success shown before app access was refreshed | Retain retry evidence, show the refresh state, and clear only after committed membership confirmation |
| Plan UI | Renewal allowance appeared ACTIVE, old payment state survived plan changes, and the benefits row could overflow | Explicit status labels, account/plan-scoped payment state, a ticking summary, and wrapping benefit columns |

Canonical payment prices remain KES 150/550/950/1350. Paid periods remain exactly 30 UTC days, with the existing fixed two-day renewal allowance. No payment is performed by the regression tests.

## Verification

Server tests cover all prices, underpayment, account/plan mismatch, fake/refunded/outgoing/conflicting evidence, failed atomic commits, concurrent receipt retries, nonce recovery, renewal boundaries, suspension and verification throttling. Profile tests cover all tier limits, concurrent creation, downgrade edits, account authentication and invalid payloads. Firestore emulator tests cover paid-plan forgery, receipt privacy, profile-create rejection, private verification budgets, screen slots and device binding. Android checks cover clock correction/restart behavior, all tier gates, status presentation, the checkout sheet, profile payloads, and offline membership/download paths.

Production results and release receipts are recorded separately after deployment. Passing an emulator test does not establish that the matching rules are live.

## Remaining operational boundaries

No signed refund webhook is configured: support must suspend an already activated membership after refunding it. Hardware device attestation, protected media delivery and a central download-device registry are absent. An offline device learns new revocations on reconnect. The audit does not claim tamper-proof enforcement on modified/rooted clients.
