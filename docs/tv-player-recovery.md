# TV player seek previews, remote input and recovery

The active player is `ui/screens/player/PlayerScreen.kt`.

Seek previews show a three-frame 16:9 filmstrip with a sliding white outline. The timeline interpolates remote seek changes and samples playback position on display frames. Both directions enter scrubbing from the focused timeline; confirm commits the selected position.

Advertised thumbnail tracks retain their original sprite coordinates, image aspect ratio, signed query strings and stream headers. WebVTT can point to a sprite rectangle or a whole image. The movie backdrop is no longer used as a scene preview.

When a route has no usable storyboard, `SeekFrameExtractor` follows the lowest video HLS rendition and decodes the selected segment locally. `.js`, `.jpg` and `.ts` are treated as opaque segment names. Supported inputs include MPEG-TS, fragmented MP4 initialization segments, byte ranges and issued AES-128 identity keys. Extraction starts only while seeking, after a 250 ms settling delay, and caches frames. It does not seek or change the playback player. Download and cache sizes are bounded. Unsupported codecs, SAMPLE-AES and encrypted MP4 initialization can leave a preview unavailable; there is no substitute poster. Android decoder behavior still needs validation on the target TV.

Media play/pause keys act on their first press while revealing hidden controls. Held toggle/next keys act once; held seek keys may repeat. Matching wake-confirm key-up events are consumed to prevent a newly focused button from undoing the first action.

Transport failures do not create provider rate limits. Propagated gate errors and stale cached waiting-video URLs do not restart a cooldown. A fresh rejection uses bounded default backoff of 60, 120, 240 or 300 seconds when no server delay is supplied. Explicit `Retry-After` remains authoritative, including waits longer than five minutes. Normal HLS paths or movie titles containing rate-limit words are not rejection evidence.

Skip Intro and credits pills now use supplied chapter boundaries or published marker records for the exact movie/episode. The active player no longer uses subtitle gaps, music keywords, a guessed 45–115 second intro, or a percentage/duration estimate for credits. Chapter tracks are excluded from subtitle menus and rendered subtitle tracks. Intro boundaries must be complete and within the current runtime; credits need a valid explicit start. Conflicting records, ambiguous editions, weak IntroDB confidence and invalid identity echoes are discarded. Missing data leaves timed pills hidden.

Public sources are IntroDB (`api.introdb.app/segments`, IMDb/season/episode) and TheIntroDB (`api.theintrodb.org/v2/media`, TMDB with season/episode for TV). Their published client schemas were inspected at [CloudStream commit 94e9558](https://github.com/recloudstream/cloudstream/tree/94e955836bdf2932bea248b2eba29652d761cc78/app/src/main/java/com/lagradost/cloudstream3/utils/videoskip). Fixtures follow those schemas; live responses were unavailable because the cloud network proxy rejected the connections. The saved environment draft adds the API and documentation domains; publishing the draft is needed for further live checks. Community markers are not guaranteed to cover every title or streaming edition.

Next Episode appears only after the actual catalog confirms a destination. Missing catalogs and series finales leave the current stream intact. Cross-season navigation uses actual listed season and episode numbers. The credits countdown pauses during pause/buffering, resumes its remaining time, and resets if the viewer seeks back before credits.
