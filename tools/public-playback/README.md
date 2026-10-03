# Public catalog resolution

Both apps bundle a CC0 Wikidata join with 31,442 native identity candidates for 31,185 distinct TMDB movie/series identities. These are candidates, not a claim that all titles play. A second public sitemap index adds 6,342 Hotstar partner movie/series candidates. Partner movie IDs are translated through official Hotstar links on the public title page; they are never assumed to be native IDs. The join uses P4947/P4983 for typed TMDB identity and P1874/P14440/P11049 for Netflix/Prime/JioHotstar.

Playback performs public catalog search, then typed native-ID lookup. Candidate IDs are checked against public title/type/year metadata, requested seasons and episodes are discovered, and issued HLS is checked. Successfully resolved native identities are cached for seven days; failed cached identities are removed and another matching route is attempted within the same time/request budget. Stream URLs and credentials are never saved in this catalog cache.

Refresh the bundled index from the repo root:

```powershell
node tools/public-playback/build-public-catalog.mjs
node tools/public-playback/build-hotstar-catalog.mjs
```

Run the cold segment audit with your TMDB configuration:

```powershell
node tools/public-playback/public-catalog-flow.mjs --tmdb-config .env.example --report public-catalog-report.json
node --test tools/public-playback/public-catalog-flow.test.mjs
```

The saved public-catalog-report.json records eleven successful video container samples among twelve titles. The general sitemap flow also verified Tumse Na Ho Payega. Twisted Metal still has no verified provider identity in this flow. This audit proves bounded HTTP media delivery, not Android decoding or availability of every episode. Catalog brand labels may map to a different hosting catalog; do not require HBO titles to use a separate HBO endpoint when the verified hosting catalog is JioHotstar.

No authentication cookies, warmup session, private credentials, guessed native IDs or modified CDN signatures are used. A provider catalog record alone cannot make unavailable media playable.

The six-hour `provider-catalog.yml` refresh also performs optional incremental Netflix movie enrichment. Netflix advertises `https://www.netflix.com/sitemap/index` in its public robots file. The job reads complete native-ID links from that index, compares its saved snapshot, and checks at most 24 new or due retry title pages per run. The first run checks a bounded backlog of published IDs absent from the existing native catalog; subsequent runs prioritize newly published IDs. This work runs in GitHub Actions, outside app startup.

An added movie mapping requires the exact Netflix page's top-level Movie identity, a valid `datePublished` or `dateCreated` release year, and one uniquely matching typed TMDB movie. TMDB canonical titles, original titles and authoritative alternative titles may match; fuzzy names, wrong years, recommendation links, nested trailer dates and ambiguous matches do not. Public TV pages without an authoritative premiere year do not create TV joins; those continue to use the typed Wikidata source. The feed schema remains version 2, and apps still verify candidates before playback.

The saved `catalogs/netflix-metadata-state.json` records complete sitemap snapshots, accepted movie joins and retry times. Invalid, truncated or unexpectedly shrinking indexes keep the last good snapshot and accepted joins. Failed title metadata retries after one, three, seven and then thirty days; requests, concurrency, response sizes and total runtime are bounded. Refresh validation must pass before either the state or app feed is published. `TMDB_API_KEY` uses the existing CI secret when configured and otherwise reads the project's existing `.env.example` configuration; logs contain counts, never API keys or request URLs.

Run metadata-only validation without Android builds:

```powershell
node --test tools/public-playback/netflix-catalog.test.mjs tools/public-playback/provider-feed.test.mjs tools/public-playback/partner-catalog.test.mjs
```
