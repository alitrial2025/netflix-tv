# Live discovery and recommendations

Phone and TV use the same UTC release policy, TMDB query plan, ranking engine, contribution protocol and private reminder format. These files live under `app/src/main/java/com/example/discovery`; only `ReleaseAdapters.kt` is platform specific.

## Release feeds

- New & Hot: a valid first release/first air date from one calendar month before today through today, inclusive.
- Coming Soon: today through three calendar months ahead, inclusive. The same-day overlap is intentional. No synthetic announcements, old-title padding or missing-date fallback.
- Existing series with a new season are not relabeled as newly released titles: these feeds use first-air dates, not unverifiable season dates.
- Movies from major OTT providers and TV from their associated networks qualify. Other films qualify as anticipated when TMDB popularity is at least 10. This is editorial curation, not a promise that our playback provider hosts an announced title.
- Both apps use the same US provider baseline (Netflix, Prime Video/Amazon, Disney+, Apple TV+, HBO/Max, Hulu, Paramount+, Peacock). Geography is deliberately shared, not inferred from the device.
- Six query groups, two pages each: at most twelve requests per refresh, one or two concurrent requests, 25-second refresh network budget, six-hour reuse. Lists are capped at 60 per feed; cached source metadata is capped at 240 entries.
- A public-metadata disk cache provides offline/return-entry feeds. A minute tick reapplies date windows and checks refresh eligibility. Process restart revalidates network data after publishing the cache. Failed requests preserve valid cached results.
- Future-dated titles are excluded from normal playable recommendations and TV hero selection. Mobile prevents direct playback of announced future titles. A release date does not guarantee actual stream availability.

## Personalized ranking

The engine combines selected genres, actual watch history, likes/dislikes, Bayesian quality, bounded TMDB popularity, recent releases, bounded community counts and a deterministic daily rotation keyed by profile and typed title identity. Genre diversity penalizes repetition among the last three picks. The expensive greedy diversity pass is limited to 60 picks, with cached genre sets; remaining results follow their base score.

Watch events require at least 120 seconds and a known duration to influence taste. Weight decays with a 21-day half-life. Completed movies (95%) leave primary recommendation picks; a completed episode does not remove the series. Dislikes are excluded from primary picks, while existing search/library access is retained. A new account starts from quality/popularity and the selected genres. No row claims a user watched something without actual history.

General catalogue and existing library lookups retain legacy numeric IDs. New feeds, counters, ranking keys and cloud reminders use `movie:<id>` / `tv:<id>` so equal numeric IDs cannot merge these records. A full migration of legacy library identity is outside this change.

## Community viewing signals and reminders

Community reads observe the top 30 titles for each of today and the previous two UTC days (at most 90 initial documents). Playback contributes after two minutes of elapsed, advancing playback; seeking, pauses, preview playback and long sample gaps do not count. Contributions are deduplicated per signed-in account/title/day, including concurrent phone/TV sessions, and capped at 20 distinct titles per account/day. These are daily unique-account signals, not unique people or verified server-side viewing totals. A modified client could misreport watching within the permitted quota; client code cannot prove watch time to Firestore rules.

Schema:

- Public: `community_trends/<UTC-day>/titles/<movie_id|tv_id>`: `key`, `day`, `viewers`, `updatedAt`.
- Account-private: `users/<uid>/discovery_votes/<day>_<typed-id>`: `key`, `day`, `createdAt`.
- Account-private: `users/<uid>/discovery_days/<day>`: `count`, `updatedAt`.
- Profile-private: `users/<uid>/profiles/<profile>/release_reminders/<typed-id>`: `key`, `enabled`, `updatedAt`.

Reminder records synchronize between devices. Disabling creates a tombstone instead of deleting the record, preventing legacy migration from re-enabling it. Only known typed legacy reminders migrate. Cached reminders are scoped to the account/profile; listeners detach on account/profile changes. Rule/connection failures never interrupt playback.

**Production rules have not been deployed.** The repository-root `firestore.rules` merges the user's supplied live rules with the new discovery/reminder matches. The previous recursive account grant is split so it cannot bypass immutable votes, quotas or reminder tombstones. All other supplied owned-data and TV pairing grants are preserved. The emulator fixture is byte-for-byte checked against this deployable file and tests both new restrictions and existing access. The workspace has no authorized Firebase CLI account; `google-services.json` is client configuration, not administrative deployment credentials. No paid Cloud Functions are required.

To activate these collections, publish the complete root `firestore.rules` in the existing project's Firebase Console → Firestore Database → Rules. Alternatively, after authenticating Firebase CLI, deploy once from either repository (both apps use the same project):

```bash
firebase deploy --only firestore:rules
```

Root `.firebaserc` points to the project already configured in both apps. `firebase.json` deploys only the rule file. Restart the apps after activation so failed pre-deployment contribution attempts can be retried in a new session. Rule activation must precede interpreting community viewing counts as live; local recommendations and TMDB feeds work independently. The existing subscription-write and public TV-session permissions supplied by the user are outside this discovery change; the new rules do not certify those policies.


## Mobile setup and TV performance

Mobile profile setup has three native steps: identity/avatar/kids mode; language/maturity/PIN/autoplay; favorite genres. The existing avatar picker is reused. Back retains the draft; cancel does not save; pending saves disable actions and failed saves remain visible. All choices use existing profile persistence; favorites migrate through Room 8→9 and sync with the TV-compatible profile fields. Language is a profile preference; this change does not translate all application UI.

TV design and motion timings remain intact. Discovery requests wait for startup preparation or idle Home, avoid full playback/active navigation, and use one request at a time on low-memory devices. Ranking runs off the main thread with debouncing. Date calculations and rail genre normalization are cached per generation. Unsafe adult fallback from an empty kids catalogue is removed.

## Validation

Shared unit tests cover calendar boundaries/leap dates, aging caches, date/OTT queries, bounded concurrency/timeouts/offline behavior, deterministic taste and daily rotation, quality/diversity and elapsed-playback qualification. TV rail regressions cover genuine announcements, actual-history labels, strict new-release dates and kids safety. Mobile native Compose tests cover the full walkthrough, PIN validation, retained drafts, pending/error saves and captured UI. The existing Room upgrade test covers the complete 7→8→9 chain without losing profiles/download paths.

`tools/discovery-validation` runs nine Firestore emulator tests: cross-device dedupe, independent accounts/typed identities, private records, forged/guest/past-day rejection, contribution quota, reminder tombstones/profile isolation malformed identities, concurrent legacy-migration protection and preservation of existing app permissions. It uses a demo project and cannot write to the live project. See its README for reproduction.

Live TMDB queries were checked on 2026-10-01. A 1,001-title ranking benchmark took about 35 ms on the cloud JVM; this is not a TV hardware frame-time result. Android device/emulator interaction performance remains unverified because interactive software-emulator testing was deferred. Full Gradle unit tests, release assembly and release lint must pass before merging.
