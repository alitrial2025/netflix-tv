# Watch-party database usage — 2026-10-22

The server now validates membership and commits the action in one Firestore transaction. Subscription, rate, room and account state are read inside that transaction. Retried transactions recheck membership; no authorization decision is cached between requests. Room mutations are staged until validation succeeds. Expected rejections commit only the rate record, so bad invite guesses, stale controls and duplicate messages still consume their existing rate budget. Unexpected failures abort the transaction.

Host heartbeats reuse presence published by a control in the last 12 seconds. If controls stop, existing 20-second heartbeats renew public presence within 32 seconds, below the apps' 45-second host timeout. Canonical account leases still refresh. Pause, seek, episode changes, new comments and real moderation changes broadcast immediately. Duplicate deliveries, unchanged moderation and existing-member re-joins avoid unnecessary mirror writes. Response shapes, subscription gates, room isolation, authenticated-only access and `Cache-Control: no-store` remain compatible with installed TV and mobile versions. No APK or Firestore rule change is required.

The comparison uses one simulated hour, 20-second heartbeats, 40-second registration, 10-second playback updates where enabled, and two comments/minute where enabled. Setup and transaction retries are excluded. This measures logical database operations and serialized mirror JSON, not production network traffic or invoices.

| Scenario | Transactions | Document reads | Document writes | Mirror updates | Serialized mirror payload |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 viewers, playing, chat | −50% | −20.7% | −14.5% | −27.3% | −27.2% |
| 8 viewers, playing, chat | −50% | −21.2% | −13.5% | −27.3% | −27.2% |
| 8 viewers, host idle, no chat | −50% | −21.4% | −0.1% | −0.6% | −0.6% |

HTTP request frequency stays the same for existing apps. Costs in production depend on concurrency, transaction retries, chat activity, authentication, transport compression and Firestore pricing. Idle-host heartbeats continue to broadcast presence as needed. Full measurements are in `watch-party-usage-20261022.json`.

Reproduce from the repository root:

```sh
node tools/viewing-usage/measure.cjs --baseline 39761509f7d15f5d58253fde09b6d48fbae75e59
node --test marketing-site/tests/viewing.test.cjs
npm ci --prefix marketing-site
npm ci --prefix tools/discovery-validation
npm test --prefix tools/discovery-validation
```

Validation includes rate admission on rejected guesses, rollback after staging a previous-room detach, transaction retry after membership revocation, concurrent controls, current client presence deadlines, and real Firestore SDK transactions in the emulator. The shared emulator runs rule tests and server transaction tests serially to avoid one suite clearing another's fixtures.
