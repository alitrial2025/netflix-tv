# Continue Watching across TV and mobile

Both apps use users/{uid}/continue_watching/{profileId}_{mediaId} as the canonical watch event, with compatibility mirrors in both legacy profile collection paths. Episode coordinates travel with their own position. Mobile converts TV episode IDs into its own coordinate format; a zero-position next episode remains resumable.

Watch timestamps describe the original event, not import or retry time. Atomic cloud transactions reject older events and update the canonical record and mirrors together. A rewind can therefore replace a larger earlier position. Removal records retain a timestamp so delayed offline saves cannot resurrect a title; watch history remains. Both apps hide playback at 95% completion, and imports preserve the original timestamp instead of making an old episode appear newly watched.

TV retains its Room outbox with corrected collection-path validation. Mobile coalesces the newest per-title event in a durable account-scoped local outbox and retries with connected WorkManager jobs. A queued event can only replay while its account is signed in. The Home row uses cloud title artwork/type when that title is missing from the currently loaded catalog.

Validation covers cross-app episode coordinates, short-video completion, remote rewind, stale events, removal followed by replay, zero-position episode transitions, and renewal boundaries. Live two-device Firebase synchronization and physical-device performance were not exercised in this cloud task. Older app versions still physically delete records, so mixed-version removals have weaker conflict handling until both apps are updated. Existing raw numeric TMDB keys can collide between film and series; a typed identity migration remains separate work.
