# TV Premium verification and membership prompt

A temporary Firestore/device confirmation failure or screen-lease heartbeat exception previously cleared the confirmed subscription to Guest. The player's plan lock also treated missing device confirmation as an unpaid membership. Together these paths could load a paid stream, then display the subscription modal for a Premium account.

TV now separates a plan/catalog restriction (upgrade prompt) from an unverified device/screen lease (verify and retry). Actual full playback still requires the live authenticated ScreenLease transaction, including active subscription, device binding and simultaneous-screen limits. Cached paid entitlement only informs the UI; it does not authorize full playback. Expired, revoked, missing and TV-ineligible plans still follow their existing access rules.

Temporary verification failures retain the last confirmed plan, stop playback and show a sanitized reconnect/retry message. Retry obtains a live lease without discarding a valid provider URL. Account switches clear transient access errors and isolate subscription responses to the owning UID.

The legacy TV upgrade modal delegates to the canonical modal. Both routes show the phone's KES prices, 30-day duration, eligible Basic/Standard/Premium plans, profiles, screens, offline allowances, catalog scope and included benefits. Mobile is excluded because it cannot authorize TV playback. The prompt uses the same Watch the full story/trailer wording, selects the current plan for renewal, supports Back dismissal and retains D-pad navigation. Payments remain in the mobile app on the same account.

Six policy regression cases cover Premium without proof, signed-out leases, genuine unsupported/expired memberships, renewal grace, sanitized verification failure, and distinct device/screen denial. Final build, full-suite, lint and visual checks are recorded after validation.
