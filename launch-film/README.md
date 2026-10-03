# NetflixPro — the release film

A 36-second, 30 fps CGI launch advertisement for the released Android TV and Android phone apps. Native 3840×2160 master, plus a 1920×1080 Lanczos export. The editable source is in this directory; final MP4s are GitHub Actions artifacts.

The film is authored in TypeScript/Three.js/Remotion. The television, remote, phone, interior, sculptural person and jointed hands are real animated 3D geometry. Camera moves, device placement, walking, the phone catch and lighting are deterministic frame-based code. Typography uses Manrope. Original music and breath, contact, click and transition effects are synthesized by scripts/soundtrack.py.

App screens are freshly rendered from production Kotlin Jetpack Compose code by Robolectric native Skia and Roborazzi in GitHub Actions. They include real TV focus movement, phone scrolling and the native details entry transition. Existing catalogue artwork is only fixture content inside the real app UI. No generated image or prior screenshot replaces the native interface. See native/README.md for provenance and output contract.

## Timeline

| Time | Shot |
| --- | --- |
| 0–7 s | Hands lower a TV onto a walnut console; the person relaxes and walks away |
| 7–11 s | Rotating remote close-up and click |
| 11–18 s | Camera enters the TV, with native DPAD browsing |
| 18–22 s | Phone drops into an articulated palm |
| 22–28 s | Phone hero, native browse and details transition |
| 28–36 s | Both released apps and npro-app.vercel.app download call to action |

## Cloud render

Workflow: `.github/workflows/launch-film.yml`. Pushes on `creative/launch-film` use `render-mode.json`; `preview` exports representative frames and native UI provenance. After visual review, set the mode to `full` and push, or use workflow_dispatch once the workflow is on the default branch.

Native Kotlin captures run in two parallel cloud jobs. Native screens are cached by Kotlin and fixture hashes. Eight cloud runners render nonoverlapping 135-frame 4K ranges. Assembly checks the eight chunks, joins silent video, muxes one continuous stereo score, creates the 1080p export, checks every frame with FFmpeg and verifies dimensions, fps, 1080 frames and audio. Final artifact: `NetflixPro-Launch-4K-and-1080p`.

No Android release build or final video render runs locally. Local development can use `npm ci`, `npx tsc --noEmit`, and `npm run dev` for Remotion Studio. Assets in public/screens and score.wav are generated cloud artifacts and are intentionally excluded from Git.

## Outputs

- NetflixPro-Launch-4K.mp4 — 3840×2160, H.264, 30 fps, 36 seconds, 48 kHz stereo AAC.
- NetflixPro-Launch-1080p.mp4 — 1920×1080, H.264, same timeline and audio.
- contact-sheet.jpg — representative encoded frames.
- verification.json — dimensions, duration, frame count, audio and SHA-256 hashes.

This advert announces app availability; playback and content availability are not implied by render resolution. Native fixtures use a synthetic Home profile and do not contain real user records.
