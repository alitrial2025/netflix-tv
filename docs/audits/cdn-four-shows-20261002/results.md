# Four-show CDN routing audit

Checked 2 October 2026 using the existing Node TV resolver probe. One normal provider handshake was shared across the titles. Queries, cookies and signatures are omitted. Provider catalogue codes below are routing buckets, not proof of a title’s original streaming publisher.

| Episode | Catalogue route | CDN | Total resolution/sample time | Result |
|---|---|---|---|---|
| Smallville S3E1 | `nf` | `s23.nm-cdn9.top` | 40.84 s | HLS + MPEG-TS sample validated |
| Silo S1E1 | `pv` | `s11.nm-cdn8.top` | 5.04 s | HLS + MPEG-TS sample validated |
| Ironheart S1E1 | `hs` | `s30.freecdn31.top` | 3.79 s | HLS + MPEG-TS sample validated |
| Mr. Robot S1E1 | `pv` | `s10.freecdn43.top` | 2.50 s | CDN video manifest HTTP 403 |

The first title included 36.58 seconds to obtain the cold provider session. The other three reused that session. These are Node resolution and bounded sample timings, not Android first-frame measurements.

## Observed paths

### Smallville S3E1

- Catalogue: `nf`; show ID `70155584`; episode ID `82171122`.
- Provider entry playlist: `https://net52.cc/mobile/hls/82171122.m3u8` (HTTP 200; authorization query removed).
- CDN video playlist: `https://s23.nm-cdn9.top/files/82171122/720p/720p.m3u8` (HTTP 200; authorization query removed).
- Sampled video segment: `https://s23.nm-cdn9.top/files/82171122/720p/8904_000.jpg` (HTTP 206; authorization query removed).
- CDN token mode observed: `su`; no signatures altered.

### Silo S1E1

- Catalogue: `pv`; show ID `0GKY3CQGOYOPDSE76BWMJN5CK3`; episode ID `0LUD8W2JDNWH13VM368DJJEENO`.
- Provider entry playlist: `https://net52.cc/mobile/pv/hls/0LUD8W2JDNWH13VM368DJJEENO.m3u8` (HTTP 200; authorization query removed).
- CDN video playlist: `https://s11.nm-cdn8.top/files/0LUD8W2JDNWH13VM368DJJEENO/720p/720p.m3u8` (HTTP 200; authorization query removed).
- Sampled video segment: `https://s11.nm-cdn8.top/files/0LUD8W2JDNWH13VM368DJJEENO/720p/7127_000.jpg` (HTTP 206; authorization query removed).
- Separate audio playlist: `https://s11.nm-cdn8.top/files/0LUD8W2JDNWH13VM368DJJEENO/a/1/1.m3u8` (HTTP 200; authorization query removed).
- CDN token mode observed: `su`; no signatures altered.

### Ironheart S1E1

- Catalogue: `hs`; show ID `1271341039`; episode ID `1271341040`.
- Provider entry playlist: `https://net52.cc/mobile/hs/hls/1271341040.m3u8` (HTTP 200; authorization query removed).
- CDN video playlist: `https://s30.freecdn31.top/files/1271341040/720p/720p.m3u8` (HTTP 200; authorization query removed).
- Sampled video segment: `https://s30.freecdn31.top/files/1271341040/720p/6221_000.jpg` (HTTP 206; authorization query removed).
- Separate audio playlist: `https://s30.freecdn31.top/files/1271341040/a/0/0.m3u8` (HTTP 200; authorization query removed).
- CDN token mode observed: `su`; no signatures altered.

### Mr. Robot S1E1

- Catalogue: `pv`; show ID `0L52QDYY6OG738LB7ILP0VB7R4`; episode ID `0RZED4V5SOLKXX2U04B6XONCIM`.
- Provider entry playlist: `https://net52.cc/mobile/pv/hls/0RZED4V5SOLKXX2U04B6XONCIM.m3u8` (HTTP 200; authorization query removed).
- CDN video playlist: `https://s10.freecdn43.top/files/0RZED4V5SOLKXX2U04B6XONCIM/720p/720p.m3u8` (HTTP 403; authorization query removed).
- CDN token mode observed: `su`; no signatures altered.

## Mr. Robot failure

The provider successfully returned its master HLS playlist. Its declared video CDN returned HTTP 403 with no workspace-proxy-block header. One retry of the identical issued URL with normal app headers also returned 403. This confirms rejection of that route from this workspace; it does not identify the precise cause or establish that every app/device is affected. Session expiry alone is not supported by this run: the same fresh session resolved the other three titles.

## Evidence and limits

- Seven existing local Node probe regression tests passed before the live run.
- Successful cases include HLS parsing and one bounded video segment sample. Silo and Ironheart also passed separate-audio playlist and sample checks.
- Some segment filenames end in `.jpg` or `.js`; the sampled bytes were identified as MPEG transport streams, not images/scripts.
- No complete episodes were downloaded. No files were uploaded to NitroFlare. No Kotlin code was changed.
- Discovery routes are point-in-time observations, not hostnames to hard-code into the apps.
- `report.json` contains the sanitized request stages, timings and statuses. Private cookies and issued URLs remain outside Git.
