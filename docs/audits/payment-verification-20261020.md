# Payment verification failure — 2026-10-20

Recent production payment requests returned HTTP 503 before subsequent attempts reached the HTTP 429 verification cooldown. A read-only diagnostic executed in Vercel's production environment using its protected merchant credential received HTTP 401 from both PayHero's transaction-status and transactions endpoints. No charge or membership activation was performed. The Mobile price remains KES 150.

PayHero currently rejects the configured merchant authorization before the service can validate receipt ownership, incoming funds or checkout binding. Correcting the protected production PAYHERO_API_AUTH value and redeploying is required; the cooldown does not establish that money was unpaid or that the receipt was redeemed. Credentials and customer receipt data are excluded from these notes and diagnostics.

The server now records safe failure categories and upstream endpoint/status only. Upstream 401/403 produces a merchant-connection support message with HTTP 503, so the phone does not mistake merchant authentication for an expired customer Firebase sign-in. The server retains all authentication, receipt, amount, ownership, rate-budget and atomic membership protections.

The mobile recovery change displays a cancellable countdown and retries only explicit VERIFICATION_BUSY responses within strict bounds. It cannot correct rejected merchant credentials or approve a payment locally. Gateway and evidence failures stop recovery and preserve the receipt for support.

Validation: 45 server/release regressions passed, including sanitized diagnostics and merchant 401/403/500 responses. Twenty focused Android payment/subscription regressions passed, including nine new cooldown recovery cases. Production credentials must be corrected by the merchant owner before successful verification can be confirmed.
