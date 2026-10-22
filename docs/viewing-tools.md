# Viewing tools

Watch together is available on phone and TV under an authenticated active membership. TV retains the TV-enabled-plan and admitted screen requirements. Each viewer resolves the title independently; rooms contain only exact typed TMDB title/episode identity, playback state, member display names and timestamps. Room codes expire after six hours and rooms allow eight devices. The host controls play/pause/seek/next; guests keep audio, subtitles and volume. Missing connectivity pauses guests when the host lease becomes stale. Different editions with durations over ten seconds apart pause rather than guessing a new timeline. Closing the dialog keeps the party; Leave/End performs canonical cleanup.

Continue on TV requires the TV application to be open, signed into the same account and admitted through its normal entry/profile flow. A handoff expires after thirty seconds and is consumed once. It resolves its own stream and retains normal plan, profile and maturity checks. It does not claim to launch a closed TV application or use Google Cast.

Backend `/api/viewing/session` verifies revoked Firebase tokens and derives UID server-side. Canonical rooms/accounts/rate limits are Admin-only collections. Realtime fanout is under existing owner-readable user subcollections, and every control action is validated against the canonical room. Client mirrors never authorize playback or grant membership.

Custom collections are scoped to the signed-in owner and selected profile, with Firestore offline caching. Exact typed title IDs prevent movie/series collisions. Stored artwork/text is display metadata; opening a title uses trusted metadata and normal title gates. Deletion leaves a tombstone so delayed offline edits do not resurrect collections.

Subtitle offsets are local per account/profile/typed title, bounded to ±10 seconds, in quarter-second steps. Positive offsets display captions later. Only text renderer clocks change; audio and video timing remain unchanged.

Mobile intro/credits controls use verified provider chapter boundaries or exact-identity published metadata. No subtitle-gap or percentage timings are guessed. Absent/invalid data hides automatic pills; manual episode navigation remains available.

Mobile saved download receipts contain local file/range hashes and non-reversible source fingerprints, never signed URLs/credentials. Checks run off the UI thread. Repair keeps valid HLS segments; progressive range repair requires exact resource fingerprint, strong matching ETag and validated byte ranges. Changed sources/validators fall back to a fresh transfer. Originals survive until a replacement is complete. Older downloads without receipts are explicitly unverified. Storage controls show actual file sizes/free space and require deletion confirmation. Shared-profile references are retained.

Validation includes native collection edit/focus tests, subtitle timing/profile isolation tests, marker identity/network/parser tests, party synchronization/server-clock tests, server host/guest/expiry/rate/auth/privacy tests, and actual local HLS/HTTP repair requests with corrupted ranges. Hardware checks should include two real signed-in devices, TV background/resume, Wi-Fi loss, different editions and process restart; emulation cannot prove all OEM remote/decoder/network behavior.
