# Request for an authorized playback-token integration

Hello,

We are integrating Android TV and Android mobile playback and would like documented, authorized API access for issuing signed CDN playback URLs. Please advise whether a partner integration is available and how to apply.

We need:

- An authenticated token endpoint that accepts an authorized account/session and a movie or exact season/episode identifier, and returns the current signed master/playback URL with its expiry time.
- Stable media identifier mapping, entitlement requirements, supported player/protocol requirements and any required playback headers. Please clarify whether audio, subtitle and segment URLs have independent signatures.
- Renewal behavior, session and URL lifetimes, allowed caching, concurrency/rate quotas, Retry-After behavior and errors that distinguish an expired session from a changed or rejected CDN route.
- Provider/CDN origin discovery and redirect behavior, so clients can follow issued URLs without hard-coding hosts, paths or signature syntax.
- Sandbox/test credentials and an approved test procedure for cold-session, warm-cache, token-expiry and route-change checks.

Our backend would keep integration credentials in a secret store and request authorized playback URLs. The Android apps would receive playback URLs rather than a private signing key. We do not need the provider's signing key exported to the apps.

Please share the integration documentation, onboarding requirements and appropriate technical contact.

Thank you.

---

This is a draft for the operator's official support/partnership channel. It has not been sent. An API endpoint or credentials cannot be assumed from current client-side URL fields. If the provider offers no integration, preserving/reusing valid provider-issued URLs remains the supported existing flow. Our own signing keys would apply only to a CDN/media service we control and are authorized to operate.
