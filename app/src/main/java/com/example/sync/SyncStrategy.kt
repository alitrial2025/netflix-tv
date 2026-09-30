package com.example.sync

/**
 * Per-field conflict resolution policy for Firestore sync.
 *
 * When a local write and a remote write both update the same field on the
 * same document, the policy determines which value "wins" — the value that
 * persists in the merged document.
 *
 * Server-stamped fields (anything ending in `At` like `createdAt`,
 * `lastUpdatedAt`, `lastUsedAt`) always use `SERVER_WINS` because the
 * server's clock is authoritative and clients should not lie about
 * timestamps.
 */
enum class ConflictPolicy {
    /** Server value wins. Client's value is discarded. */
    SERVER_WINS,

    /** Client value wins. Server's value is overwritten. */
    CLIENT_WINS,

    /** Larger numeric value wins. Used for monotonic counters (e.g. playback position). */
    MAX_WINS,

    /**
     * For List<T>: union the two lists, deduplicate.
     *
     * Real scenario: My List (the user's "Save for later" row). Phone adds
     * Movie A, TV adds Movie B offline. Both devices come online. With a
     * naive last-write-wins the user would lose either A or B. With
     * SET_UNION, both are preserved.
     *
     * Why not LIST_MERGE: list order doesn't matter for My List — the
     * server re-sorts by `addedAt` when serving the row. Deduplication
     * is the only requirement.
     */
    SET_UNION,

    /**
     * For List<T>: keep the order of the client, append server-only items.
     *
     * Real scenario: a user-editable ordered list where order carries
     * meaning (e.g. the home-screen "Top Picks" reorder). The client's
     * drag-and-drop order is more recent than the server's cached order,
     * so we keep it; server-only items are appended at the end so they
     * aren't silently dropped.
     */
    LIST_MERGE,

    /** Field is locally-owned and never synced (e.g. UI-only state). */
    LOCAL_ONLY,

    /**
     * Field is server-owned and read-only from the client.
     *
     * Real scenario: `subscription.tier`. The client's local code has no
     * legitimate path to write the user's paid tier — that authority is
     * the billing webhook / server-side function. If we ever see a
     * client-side write to a SERVER_ONLY field, the policy is the
     * defense-in-depth check that prevents a tampered client from
     * self-promoting to plan_premium. The Firestore security rules are
     * the primary gate; this policy is the in-app audit trail.
     */
    SERVER_ONLY,
}

/**
 * The complete per-field conflict policy for every document the app syncs.
 *
 * Add new fields here as they're added to the schema. The key is the Firestore
 * collection.document.field path; the value is the policy.
 */
object SyncStrategy {
    /**
     * Profile document policy.
     * Path: users/{userId}/profiles/{profileId}
     */
    val PROFILE: Map<String, ConflictPolicy> = mapOf(
        "name"             to ConflictPolicy.CLIENT_WINS,         // last edit wins
        "avatarUrl"        to ConflictPolicy.CLIENT_WINS,
        "isKids"           to ConflictPolicy.CLIENT_WINS,
        "maturityRating"   to ConflictPolicy.CLIENT_WINS,
        "language"         to ConflictPolicy.CLIENT_WINS,
        "autoPlayNextEpisode" to ConflictPolicy.CLIENT_WINS,
        "autoPlayPreviews" to ConflictPolicy.CLIENT_WINS,
        "color"            to ConflictPolicy.CLIENT_WINS,
        "pin"              to ConflictPolicy.CLIENT_WINS,         // shared PIN digest
        "pinHash"          to ConflictPolicy.CLIENT_WINS,         // legacy alias
        "createdAt"        to ConflictPolicy.SERVER_ONLY,         // server stamps once
        "lastUsedAt"       to ConflictPolicy.SERVER_WINS,         // server is authoritative
        "id"               to ConflictPolicy.SERVER_ONLY,         // never changes
    )

    /**
     * Continue Watching document policy.
     * Path: users/{userId}/profiles/{profileId}/continueWatching/{movieId}
     */
    val CONTINUE_WATCHING: Map<String, ConflictPolicy> = mapOf(
        /**
         * Why MAX_WINS for `positionMs`:
         *
         * Scenario: User watches a movie on Phone until 30:00, then closes
         * the app. They open the app on TV later that evening and resume
         * at 30:00, but accidentally seek back to 25:00. They close the
         * app before saving. The next time they open Phone, the Phone-side
         * offline write (30:00) is pending; the TV-side write (25:00) is
         * also pending. With last-write-wins they'd lose 5 minutes of
         * progress. With MAX_WINS, the phone's 30:00 wins.
         *
         * Why not CLIENT_WINS or LAST_WRITE_WINS:
         * - LAST_WRITE_WINS is racy: clock skew between devices could pick
         *   the older write.
         * - CLIENT_WINS means whichever device wrote last is
         *   authoritative, which is fine for a single device but bad for
         *   multi-device.
         * - MAX_WINS is the safest "user is most likely to have progressed
         *   further" heuristic.
         */
        "positionMs"       to ConflictPolicy.MAX_WINS,            // user progressed further on one device
        "durationMs"       to ConflictPolicy.SERVER_WINS,         // server has the canonical duration
        "season"           to ConflictPolicy.SERVER_WINS,
        "episode"          to ConflictPolicy.SERVER_WINS,
        "completed"        to ConflictPolicy.CLIENT_WINS,         // whichever device finished first
        "lastUpdatedAt"    to ConflictPolicy.SERVER_WINS,         // server timestamp
    )

    /**
     * My List entry policy.
     * Path: users/{userId}/myList/{movieId}
     */
    val MY_LIST: Map<String, ConflictPolicy> = mapOf(
        "addedAt"          to ConflictPolicy.SERVER_WINS,         // server timestamps
        "userId"           to ConflictPolicy.SERVER_ONLY,
        "movieId"          to ConflictPolicy.SERVER_ONLY,
    )

    /**
     * User subscription policy.
     * Path: users/{userId}/subscription
     */
    val SUBSCRIPTION: Map<String, ConflictPolicy> = mapOf(
        /**
         * SERVER_ONLY for every billing-owned field:
         *
         * Scenario: the user tampers with the client (e.g. via a debug
         * build or a malicious repackage) and tries to write
         * `subscription.tier = "plan_premium"`. The CLIENT_WINS default
         * would have allowed it. SERVER_ONLY makes the policy refuse the
         * write entirely and log a `policy=SERVER_ONLY skip` line. The
         * Firestore rules should also refuse the write, but the in-app
         * check is the audit trail that catches a misconfigured rule
         * before the user notices.
         */
        "tier"             to ConflictPolicy.SERVER_ONLY,         // NEVER client-writable
        "isActive"         to ConflictPolicy.SERVER_ONLY,
        "renewsAt"         to ConflictPolicy.SERVER_ONLY,
        "trialEndsAt"      to ConflictPolicy.SERVER_ONLY,
        "startedAt"        to ConflictPolicy.SERVER_ONLY,
        "paymentMethod"    to ConflictPolicy.SERVER_ONLY,
        "lastVerifiedAt"   to ConflictPolicy.SERVER_ONLY,
        "autoRenew"        to ConflictPolicy.CLIENT_WINS,         // user can toggle in settings
        "cancellationReason" to ConflictPolicy.SERVER_ONLY,
        "gracePeriodEndsAt" to ConflictPolicy.SERVER_ONLY,
    )

    /**
     * TV session pairing policy.
     * Path: tvSessions/{sessionId}
     */
    val TV_SESSION: Map<String, ConflictPolicy> = mapOf(
        "userId"           to ConflictPolicy.SERVER_ONLY,
        "userEmail"        to ConflictPolicy.SERVER_ONLY,         // set at pairing, never changed
        "pairedAt"         to ConflictPolicy.SERVER_ONLY,
        "lastSeenAt"       to ConflictPolicy.SERVER_WINS,         // heartbeat
        "deviceId"         to ConflictPolicy.SERVER_ONLY,
    )

    /**
     * Continue Watching fields used by the (flat) alternate path.
     *
     * The app writes the same data to two paths: a flat
     * `users/{uid}/continue_watching/{docId}` and a nested
     * `users/{uid}/profiles/{pid}/continue_watching/{movieId}`. Both paths
     * share the same field-level policy, so this map aliases
     * [CONTINUE_WATCHING] for the flat path. The resolver uses the
     * collection name in the lookup, so both `continueWatching` and
     * `continue_watching` (snake_case) are accepted.
     */
    val CONTINUE_WATCHING_FLAT: Map<String, ConflictPolicy> = CONTINUE_WATCHING
}
