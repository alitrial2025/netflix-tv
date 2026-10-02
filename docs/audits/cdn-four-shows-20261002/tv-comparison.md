# TV versus Node follow-up

Smallville S3E1: provider master declares zero external audio tracks. The sampled 720p transport stream contains H.264 High video at 1280x720 and AAC-LC stereo audio at 48 kHz. The sample successfully decoded a video frame with FFmpeg. This rules out missing separate-audio wiring for the tested route; it does not prove Android playback or identify the TV failure.

Mr. Robot S1E1: a fresh route discovery using the existing fresh session again selected pv episode 0RZED4V5SOLKXX2U04B6XONCIM and s10.freecdn43.top. Its video playlist still returned HTTP 403. The original request, normal-header retry and fresh-route test all reject this workspace's issued route. Signatures were recent, not near the ten-hour expiry. The same session served the other three titles.

The TV uses its own network location, provider session, search/route caches, and ExoPlayer. Its Kotlin resolver also performs bounded CDN rediscovery, unlike the original Node run. That retry behavior alone did not resolve the workspace rejection in the follow-up. Device logs are needed to compare its actual Mr. Robot route and identify where Smallville fails.

There is no connected Android device/emulator in this workspace at the time of this follow-up. No Kotlin changes were made based on these observations.
