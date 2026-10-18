# Black Clover episode order

Verified 18 October 2026 against TMDB TV 73223 (2017), its episode group `5ea411e1bdc34c002625e6df`, the Netflix public model for show `80238012`, and all 170 currently published provider episode rows. TMDB's default season 1 holds the original 170 episodes. Its separate continuation season must not be mistaken for Netflix's second bundle of the original series.

| Display season | Canonical TMDB season | Absolute episodes | Netflix season ID | Count |
|---|---|---|---|---|
| 1 | 1 | 1–51 | 80237813 | 51 |
| 2 | 1 | 52–102 | 81313521 | 51 |
| 3 | 1 | 103–154 | 81399561 | 52 |
| 4 | 1 | 155–170 | 81582089 | 16 |

Both apps present these four verified groups, with absolute episode numbers retained. Episode IDs, stored progress and existing mobile download IDs keep their canonical TMDB coordinates. Next Episode follows the display groups but returns canonical playback coordinates. No future fifth provider season is inferred from TMDB's continuation metadata; a new provider season needs a separately verified ordering update.

Two playback defects combined: both episode resolvers stopped after five pages of ten rows, hiding episode 51, and the default TMDB season was assumed to equal the provider's season. Provider labels cannot repair the mismatch alone: the first episode in Netflix season 3 is called E104 but is canonical episode 103, and the series finale is called E171 but is canonical episode 170.

`SeriesEpisodeOrder` binds the alternate layout to the exact TMDB and Netflix show IDs. The resolver verifies the complete 170-episode TMDB season identity, then joins the selected provider season's explicit episode titles to canonical episode identities. It never computes opaque IDs or assigns episodes from response position. Sixteen translation/typographic aliases are explicitly reviewed and bound to their TMDB episode ID, canonical title, provider title and provider episode ID. For example, “A Young Man's Vow” and “The Boys' Promise” identify the same second episode, while “Nero Reminiscences… (1)” is published as “Nero Reminisces... Part One”. The rest match after the existing title normalization. Duplicate identities, incorrect metadata, conflicting seasons and unrecognized aliases are rejected.

Verified episode-cache entries store provider season/episode labels separately from canonical coordinates. Old entries without this proof cannot shortcut the new title join. NewTV receives the verified provider labels for an exact resolved content ID; its catalog cannot select a numerically matching but different episode first. Netflix/Prime/Hotstar fallback, request cooldowns, the existing 28-second and 32-request resolution bounds, subscriptions and offline admission remain in force. Pagination can reach page six and beyond, with a bounded page limit rather than the old five-page cut-off.

Regression fixtures retain only public identity fields from the checked TMDB and provider metadata. Android tests join all 170 episodes, check boundaries and page six through the real resolver, reject mismatches, restore mapped cache coordinates, and cover next-episode transitions and existing mobile resume/download identities. Node tests exercise the corresponding public flow and ordinary pagination. Live media checks and their precise results are stored separately in `/workspace/.netflix-tv-tools/review/black-clover-20261018`; catalog identity coverage is not a claim of Android decoded playback for all 170 episodes.

The live check resolved canonical episode 51 to Netflix episode 80238276 after reading page six. Its returned f6.freecdn4.top CDN was blocked by this cloud environment's CONNECT policy, so media bytes for that episode were not verified here. Canonical episodes 103 and 170 resolved to 81399562 and 81582107 respectively and each returned a 65,536-byte MPEG-TS video sample from s25.nm-cdn6.top. No physical Android first-frame proof or all-170 media playback claim is made. The Node audit now uses Android's desktop public-Netflix request headers, avoiding the mobile-browser app-intent redirect.
