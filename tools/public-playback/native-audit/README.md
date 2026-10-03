# Native playback audit

The opt-in GitHub workflow runs the actual production Kotlin resolver in Robolectric, then follows its issued HLS playlist and requests the first media segment. It does not replace app lookup with a Node implementation, invent provider URLs, send authentication cookies or warm sessions.

The four regression selections exercise Stranger Things seasons 1 and 4, Lioness season 1, and Grand Theft Auto VI: An Extended Look (2026). TMDB identities in the test select catalogue entries; they are not new production provider seeds. Runtime discovery and the normal catalogue choose the native provider and episode IDs.

The artifact `native-provider-playback-audit` contains sanitized IDs, provider, HTTP status, segment sample hash, request count and timings. It omits issued URLs and credentials. A failed route fails the job and retains its report. Availability can change independently of correct identity resolution; an HTTP authorization or rate-limit response is not bypassed.

The workflow copies this test into cloud-only test sources. It runs on the identity branch and can subsequently be dispatched manually. No local Android build is necessary. Segment transfer verification is distinct from a full on-device decoder or playback test.
