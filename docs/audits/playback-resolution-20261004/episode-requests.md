# Episode transition request audit — 4 October 2026

Request tracing found these independently unnecessary provider calls:

- Both TV player implementations resolved the next full episode eight seconds after current playback started. This fetched playlist/master/child manifests even if the viewer never chose Next. It has been removed; next episode selection still checks episode metadata when needed.
- The public resolver fetched an HLS master and first video playlist to verify the source, then Media3 fetched the same signed manifests again. Verified static VOD manifests now pass to Media3 once under the exact signed URL, request headers, cooldown revision and a 30-second TTL. Refreshes, live playlists and different credentials use the server.
- Multiple mobile resolver instances could run the same provider resolution concurrently. Playback and downloads now use one application resolver; the public layer has a per-selection gate and expiry-aware result cache. Explicit Retry evicts that result.
- A CDN 503 for a cached next episode triggered ID eviction/catalog repair. Repair now requires an expired/invalid manifest or a missing route; transient server failures stop at the already verified identity. Genuine unavailable-route/404 alternate-provider fallback remains.
- Mobile Smart Downloads checked and downloaded the next episode at 95% completion while foreground playback/countdown remained active. New automatic work is deferred until closing the player; queued automatic transfers yield before HTTP requests and release transfer capacity/checkpoints when foreground playback resumes. User-initiated downloads and manual resume remain available.
- Repeated Next dispatches are ignored while the new selection is loading/buffering on TV or resolving on mobile.

Regression evidence (`EpisodeRequestVolumeTest`, identical in both repos):

- Selecting fixture S1E1, then E2, then E3: **10 HTTP requests total**: one verified show lookup plus three playlist requests, three masters and three video manifests. Media3 makes **zero additional HTTP requests** for the already checked startup master/video manifest pair. No speculative E4 request, search, cookie or warming call occurs.
- Eight overlapping requests for the same episode: **four HTTP requests total**, one resolution. Explicit eviction repeats only playlist/master/video, raising the total to seven.
- First selected episode with HTTP 503: three requests and immediate failure; no alternative catalog search.
- Next E2 with HTTP 503: six total including successful E1 (four) and E2 playlist/master (two); the verified E2 ID remains cached.
- Handoff tests cover exact signed URL/auth header isolation, single consumption, cached URI, expiration, live-playlist exclusion and cooldown refusal.

`AutomaticDownloadPlaybackGateTest` covers zero automatic next/curator requests while the player is active, resumption on close, yielding before the next segment, manual request access, and cancellation without a delayed request.

These are deterministic request-count regressions, not a claim that the upstream provider can never limit a client IP. The known `/files/220884` nine-minute waiting video and HTTP 429 still stop playback/provider requests. Already in-flight requests can finish during a foreground transition; automatic work yields before the next resource request. Full test/build results are recorded by the release coordinator.
