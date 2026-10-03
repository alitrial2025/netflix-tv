# Direct CDN timing probe

The Android apps use the public resolver in `tools/public-playback`; the normal-session diagnostics below are standalone historical tools. `season-probe.mjs` now delegates to the catalog audit instead of scanning nearby numeric IDs. `zero-cookie-test.mjs` delegates to the bounded public flow for Smallville S4E8, preserving issuer-provided CDN URLs and normal TLS verification. Set `TMDB_API_KEY` or pass `--tmdb-config`; reports omit credentials and signed query values.

## Season discovery and catalog reuse

`season-catalog-audit.mjs` collects season IDs only from a normal authenticated
show catalog. It samples the first episode page for each returned season,
rejects explicit authorization errors and conflicting season labels, and saves
a cache scoped to provider origin, OTT catalog, show ID and a 24-hour TTL.
It does not guess nearby IDs, forge tokens, click advertisements, or claim that
a season label alone proves show identity. Show provenance comes from the
authenticated catalog response for the requested show.

```bash
node tools/cdn-audit/season-catalog-audit.mjs \
  --session-file /private/valid-session.json \
  --private-dir /private/catalog-audit \
  --catalog /private/smallville-catalog.json --report /private/catalog-report.json
node tools/cdn-audit/season-catalog-audit.mjs --reuse-cache \
  --catalog /private/smallville-catalog.json --report /private/cache-report.json
node --test tools/cdn-audit/season-catalog-audit.test.mjs
```

The second command reads metadata offline, with no handshake or network calls.
Only the first page of each season is sampled; `hasMorePages` must be respected.
This standalone diagnostic does not change Android behavior or establish
cookie-free playback.

On 2026-10-02 a four-request, no-cookie check confirmed root `/search.php`
returned the exact Smallville show, mobile search returned Top Searches,
`/mobile/post.php` returned Invalid User, and the previously known season ID
70037632 returned ten S4 episodes beginning with Crusade, with another page.
The normal-session audit failed before catalog discovery: verification kept
returning "Waiting for your ads click" and issued no session cookie. All ten
season mappings and a fully cookie-free discovery flow remain unverified.

Requirements: Node 20+, curl; ffmpeg for optional sample decoding. Uses the app's normal mobile handshake, authenticated search, exact season/episode lookup, provider route discovery, then direct CDN playlists and segment bytes. Default targets are Mr. Robot S1E1 and Smallville S4E8.

```bash
node tools/cdn-audit/probe.mjs --private-dir /tmp/netflixpro-cdn-private \
  --report /tmp/netflixpro-cdn-timing.json --decode-sample
node --test tools/cdn-audit/probe.test.mjs
```

Reuse an existing normal session without another handshake:

```bash
node tools/cdn-audit/probe.mjs \
  --session-file /tmp/netflixpro-cdn-private/session.json \
  --private-dir /tmp/netflixpro-cdn-warm \
  --report /tmp/netflixpro-cdn-warm-timing.json --decode-sample
```

`--base-url` selects the currently configured provider origin; `--trigger-base-url` is useful for a local deterministic fixture. No account password is accepted on the command line. The standalone provider handshake is independent of Firebase app sign-in.

The private directory must stay outside Git. It has0700 permissions; cookies, request configs, response bodies, signed playlists, session.json, last-result.json and sampled media bytes have0600 permissions. Curl uses normal configured proxy settings. Private configs keep signatures and cookies out of process arguments. Only the sanitized timing report should be shared. It contains endpoint host/path and query **names**, never query values, cookies, response bodies or signed URLs. Exit2 means at least one target failed; inspect its stage rather than treating every failure as cookie expiry. HTTP 429/waiting-video results stop the audit instead of causing a request storm.

A cold run includes one handshake. The next title reuses that session. A repeated successful target reuses catalog/episode/route discovery and requests the CDN directly. Saved sessions expire with the normal ten-hour safety margin. Authorization failure is reported; the tool does not bypass challenges, forge authentication or extract signing secrets. Change the normal provider/session input and rerun to investigate service-side failures.

HLS, container sync/box signatures and optional ffmpeg first-sample decoding provide stronger evidence than HTTP 200. Only the first two 256 KB segment samples are fetched by default. This is not a whole-episode download, Android playback/DRM validation, or an app first-frame performance measurement. Encrypted HLS is reported as requiring its normal player rather than treated as clear media.

Controlled reconstruction test, using an already issued normal session/result:

```bash
node tools/cdn-audit/compare-tokens.mjs \
  --session-file /tmp/netflixpro-cdn-private/session.json \
  --issued-result /tmp/netflixpro-cdn-private/last-result.json \
  --private-dir /tmp/netflixpro-cdn-comparison \
  --report /tmp/netflixpro-cdn-comparison.json
```

This tests the original provider signature, byte-identical reconstruction from issued fields, and one request using the app's public **provider-master** token formula at the CDN. Reassembling issued fields is reuse, not independent token issuance. No signing-secret extraction or key guessing occurs. The first two cases fetch real media samples and require a decoded sample frame; the last case records whether the CDN accepts HLS. A CDN200 authorization-error body counts as rejection.

## Four-show route audit

```bash
node tools/cdn-audit/audit-four-shows.mjs --report /tmp/four-shows-report.json
node tools/cdn-audit/audit-four-shows.mjs --help
```

Checks Smallville S3E1, Silo S1E1, Ironheart S1E1 and Mr. Robot S1E1 with
one normal session and one bounded video sample per title. A shared session
failure or rate limit stops further requests. The report includes catalogue
bucket, show/episode IDs, requested CDN hosts, timings and HTTP outcomes;
query values and signatures stay private. A CDN host appearing in the report
does not imply successful playback: inspect status and media evidence.
The historical snapshot and TV comparison are in
`docs/audits/cdn-four-shows-20261002/`. Hosts are discovery results, not constants
to hard-code into an app.
