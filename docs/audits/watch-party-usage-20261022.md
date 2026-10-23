# Watch-party database usage experiment — ROLLED BACK

**Status: rolled back.** Production request logs showed repeated 503 failures and 300-second timeouts. The Firestore availability probe subsequently returned HTTP 429 `RESOURCE_EXHAUSTED: Quota exceeded`, and the user's console showed approximately 25,000 writes against the 20,000 daily free-write limit. The outage persisted after the rollback. Quota exhaustion is confirmed; transaction contention or an optimization regression was not established. Production was rolled back as a precaution to `dpl_8aNuKD4PzyYVG6bMJt3HTuqxkQAQ`, and the source has been restored to the preceding independent admission and mutation transactions. The measurements below describe the historical experiment, not current production savings. Same-account, multi-device tests cover the restored implementation.

## Historical experiment — 2026-10-22

The experiment validated membership and committed the action in one Firestore transaction. Subscription, rate, room and account state were read inside that transaction. Retried transactions rechecked membership; no authorization decision was cached between requests. Room mutations were staged until validation succeeded. Expected rejections committed only the rate record, so bad invite guesses, stale controls and duplicate messages still consumed their existing rate budget. Unexpected failures aborted the transaction.

Host heartbeats reuse presence published by a control in the last 12 seconds. If controls stop, existing 20-second heartbeats renew public presence within 32 seconds, below the apps' 45-second host timeout. Canonical account leases still refresh. Pause, seek, episode changes, new comments and real moderation changes broadcast immediately. Duplicate deliveries, unchanged moderation and existing-member re-joins avoid unnecessary mirror writes. Response shapes, subscription gates, room isolation, authenticated-only access and `Cache-Control: no-store` remain compatible with installed TV and mobile versions. No APK or Firestore rule change is required.

The comparison uses one simulated hour, 20-second heartbeats, 40-second registration, 10-second playback updates where enabled, and two comments/minute where enabled. Setup and transaction retries are excluded. This measures logical database operations and serialized mirror JSON, not production network traffic or invoices.

| Scenario | Transactions | Document reads | Document writes | Mirror updates | Serialized mirror payload |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 viewers, playing, chat | −50% | −20.7% | −14.5% | −27.3% | −27.2% |
| 8 viewers, playing, chat | −50% | −21.2% | −13.5% | −27.3% | −27.2% |
| 8 viewers, host idle, no chat | −50% | −21.4% | −0.1% | −0.6% | −0.6% |

HTTP request frequency stays the same for existing apps. Costs in production depend on concurrency, transaction retries, chat activity, authentication, transport compression and Firestore pricing. Idle-host heartbeats continue to broadcast presence as needed. Full measurements are in `watch-party-usage-20261022.json`.

The historical measurement was produced at experiment commit `9b8fba457b3fe84eebd874818c4d76ed79820850`. Running the comparison from the restored source reports no reduction against the stable baseline. Validate the current implementation from the repository root:

```sh
node --test marketing-site/tests/viewing.test.cjs
npm ci --prefix marketing-site
npm ci --prefix tools/discovery-validation
npm test --prefix tools/discovery-validation
```

Validation includes rate admission on rejected guesses, rollback after staging a previous-room detach, transaction retry after membership revocation, concurrent controls, current client presence deadlines, and real Firestore SDK transactions in the emulator. The shared emulator runs rule tests and server transaction tests serially to avoid one suite clearing another's fixtures.
