# TV and mobile playback changes

Both apps verify the authenticated account, device binding and paid screen admission at application entry. Their splash waits for account admission and initial local profile/catalog preparation. Failed entry has an explicit retry and sign-out path; a timeout does not silently open the app. TV authentication now records the Firebase-authenticated owner before admission.

The admitted session belongs to the current account and this app process. Player entry, source resolution, episode transitions and Retry use local entitlement and admission state. They do not start another Firestore membership transaction. Navigation does not release and immediately reacquire a screen slot. Sign-out and app-session disposal release the claim.

The entry claim starts a background refresh after 20 seconds. A temporary connection error preserves the already verified membership and admitted session. Explicit access denial, membership revocation, account changes and the existing subscription expiry rules still stop unauthorized playback. A server-confirmed plan change invalidates the old proof and prepares the new plan. Unverified cached preferences cannot admit a new paid session.

A disconnected active session can outlast Firestore's 45-second screen-slot reclaim interval. This is the deliberate tradeoff required to keep network interruption from stopping a verified viewer: strict concurrency during an outage would require pausing that viewer. Online transactions still enforce admission and reject unavailable screen slots; reconnect refreshes in the background.

Playback source order is:

1. The cookie-free NewTV API, using an exact verified provider title/episode identity. API-base discovery is cached for 24 hours. The API route has a bounded 12-second budget and checks the returned master, advertised URI syntax and representative audio/video/subtitle renditions. It retains the master for adaptive playback and alternate tracks.
2. The provider's direct public HLS endpoint, attempted without cookies, fabricated tokens or an authentication workaround. It has a six-second budget.
3. The existing issued-playlist resolver, retaining its prior behavior.

The guide's fake-CAPTCHA, crafted-token and universal-token bypass instructions are not part of this implementation. No third-party access control or geographic restriction is bypassed.

Exact season/episode selection remains required; an unavailable mapping does not become a guessed content ID. Cold episode discovery can still depend on the existing public provider metadata. A provider cooldown can prevent that prerequisite when no exact identity has previously been verified.

NewTV and the existing provider have independent rate-limit state. Media loading carries its source identity into the guard so one route's limit does not block a healthy alternative. A provider's Retry-After blocks further requests to that provider, and repeated user taps share in-flight resolution. Explicit source invalidation clears both the wrapper cache and the lower resolved-URL cache while retaining exact identity metadata. A real nine-minute video is no longer classified as rate limited solely by duration.

Validation includes Android builds, unit/Robolectric/Compose tests and lint for each app, with regression fixtures for startup admission, transient versus authoritative failures, account/expiry isolation, route order, malformed manifests, safe URLs, cancellation, retry budgets, cached discovery, provider backoff and failed-URL eviction. Logs and exact final counts are recorded in this workspace's final report.

Live checks from the cloud environment found that mobiledetect.app discovery returned HTTP 200 and identified tv.imgcdn.kim, while the NewTV player endpoint returned HTTP 403. Fixture results prove client behavior, not current access to that external service or TV/device playback. No release signing, deployment or production Firebase change was performed.
