# Playback session warming and renewal plan

## Required behavior

Play must establish a valid session without a Home dwell. Background warming is an optimization, not an authentication prerequisite. A usable session requires both addhash and t_hash_t, a valid timestamp and a matching generation. An expired or revoked cookie must retire its dependent nonce, manifests and routes. Home warming must stay away from initial paint, remote scrolling, auth/profile flows and Guest accounts.

## Findings and implemented corrections

| Path | Finding | Correction |
| --- | --- | --- |
| Fresh auth | Catalog parsing competes with the keyboard | Defer initial catalog loading until authenticated/Guest entry |
| Home | Warming could start for an ineligible viewer | Require an authenticated account, selected profile and active TV entitlement; reevaluate when entitlement arrives |
| Home first frame | Optional work must wait for a usable painted Home and quiet remote | Retain the existing first-frame gate, 3 s delay and 1 s input quiet period |
| Details | Separate native warming ran immediately before preview resolution | Remove redundant warming; allow first paint and quiet input, then use the existing resolver |
| Play during native warming | Warmup-job join could consume up to 20 s before waiting on the session mutex | Remove the separate job join; join cookie generation directly |
| Native warmup during Play | Native-only work could finish without the foreground recovery option | Foreground demand promotes that same handshake; keep one generation under the existing mutex |
| Native handshake exception | Initial fetch errors bypassed browser recovery | Preserve cancellation/rate-limit/session-change errors; ordinary native failures reach foreground fallback |
| Low-RAM foreground | Memory classification could disable the necessary recovery path | Use installed WebView availability; background work still cannot open a WebView without a foreground request |
| Viewer leaves | A promoted background WebView could outlive the viewer | Cancel promoted browser work when the last foreground request ends; run cleanup |
| Natural ten-hour expiry | Newer dependent tokens could remain after their cookie expired | Clear cookie/token/route/manifest caches together and advance the generation; prevent cache hits from bypassing an expired session |

## Scheduling and priority

1. Auth/splash/profile picker: display UI and obtain account/profile data; no provider session warming.
2. Home: after usable paint, selected profile and TV entitlement, wait for the existing delay/quiet gate. Verify a recent complete session or start one native handshake. Backoff remains 1 minute, doubling to a 10-minute cap.
3. Scrolling/navigation: postpone queued work. Preserve an already useful native cookie handshake for potential playback; do not restart it on every focus move.
4. Details: paint first and allow remote input to settle. Resolve the actual preview through the same coordinator; reuse eligible cached media.
5. Play: enter foreground demand immediately within its existing 58 s total budget. Cancel a warmup still waiting on an idle gate. Join an active generation directly, enabling browser recovery if native work fails or is incomplete. Do not wait separately for background verification to finish.
6. Recovery: retain server-revocation retry, atomic complete-cookie persistence, generation checks and exact expiry margins. Rate limits retain Retry-After/backoff; do not treat HTTP 429 as cookie expiry or start parallel handshakes.
7. Exit/cancellation: release foreground demand and stop promoted browser work. Existing playback ownership/progress saving continues to control shared-player handoff.

## Evidence and validation

The ten native stream attempts recorded in streams.json preceded these pipeline corrections. All failed to render a frame, including Mr. Robot; those timings are a baseline, not results for the new recovery path. Native logs showed handshake failures and incomplete verification cookies. Passing unit tests cannot prove provider availability.

New deterministic checks cover a foreground request joining an already locked native handshake without a second generation; no browser work for background-only demand; cancellation cleanup; multiple viewers; timeout release; natural ten-hour expiry and its safety margin; and leaving Home during the quiet countdown. Existing session-rejection, token timestamp, cookie-completeness, remote-input gate and episode-resume tests remain required.

## Runtime acceptance checks still required

- Cold Play with no cached cookie and no Home dwell: one generation, complete cookies, resolved media, first video frame.
- Warm Play: cache hit without a handshake; compare first frame with the cold run.
- Cookie at ten hours and early server revocation: discard dependent tokens and renew once; verify playback, not merely a successful HTTP status.
- Play while Home warming is queued, in native postback, in verification, or in backoff: join/promote useful work without the removed 20 s job wait.
- Leave/reenter and rapid title changes: no stale-generation commit, duplicate WebView, browser work after foreground cancellation, or wrong shared-player owner.
- HTTP 429/offline/provider downtime: bounded visible error, normal retry policy, no request storm and no incomplete-cookie acceptance.
- Low-RAM physical TV and modern WebView: browser recovery, scrolling/first paint and memory measurements together.

Use debug monotonic markers warmup_requested_to_start, warmup_native_finished/verified/incomplete, session_wait_for_generation, player_resolution_finished and player_first_frame. Report cookie-generation, content lookup, manifest preparation and first visible frame separately; host ADB time is not app latency. Change timeout/debounce constants only after these measurements show where the budget is spent.
