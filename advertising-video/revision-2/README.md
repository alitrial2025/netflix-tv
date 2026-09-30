# Netflix Pro — revision 2

Completed file: [NetflixPro-TV-3min-v2-1080p60.mp4](../NetflixPro-TV-3min-v2-1080p60.mp4), saved on 28 September 2026.

Verified: 180.0 seconds, 10,800 frames, 1920 × 1080 at 60 fps, H.264 picture, stereo AAC at 48 kHz, 12 chapter markers and 41,241,301 bytes (about 41 MB). Twelve encoded samples were decoded and visually reviewed, including the fixed frame during vertical row movement, the selected-card transition, main details, the expanded information panel, landscape recommendations, episodes and subtitle selection. `Video-Verification.json` records the checks; `Encoded-Video-Check-v2.jpg` shows the sampled frames. The render has completed and its processes have exited.

The revised 180-second film uses faster actions, full-screen app views, split compositions, detail insets, pans and transitions motivated by the selected card. The original film is preserved.

The Home reconstruction follows the shared animated focus position in HomeMotion.kt, computeRowAlpha in HomeScreen.kt, the fixed viewport ring, the 395dp row interval and the 35% horizontal hero slide. The invented title area above browsing rows has been removed.

Details follows the implementation imported by MainActivity: bottom-aligned content, native control sizes, yellow rating badge, generic serif title fallback, transparent bottom tabs and the two right-hand information cards. Episodes uses the real full-screen modal and its 230/290 × 150dp cards.

Source-Fidelity.json records the source mappings and geometry. Catalogue data is illustrative. This is a code-rendered demonstration, with Windows Georgia standing in for Android's generic serif fallback. Review copy is labelled as sample text. No Android build is performed.

Rendering remains sequential, with one encoder thread, Windows idle priority, CPU pacing and cooling pauses. Revision caches are keyed to both renderer files, so stale chapters cannot enter a changed edit.

Run `python ../render_video_v2.py --storyboard-only` for still checks, `--preview` for the short motion review, or omit arguments for the full film.
