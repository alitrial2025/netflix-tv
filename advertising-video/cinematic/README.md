# NetflixPro — Entertainment. Everywhere.

A three-minute TV + mobile film following the supplied cinematic brief: 3840 × 2160, 30 fps, fourteen separately encoded sequences, an original stereo score and sound design. The camera uses perspective projection, device orbits, screen dives, poster portals, a depth tunnel, floating interface planes, a shared device stage and an outlined NetflixPro end card.

## The UI is Kotlin

The screen imagery is exported from production Compose code, not reconstructed in Python. The capture harness advances the actual Compose animation clock, dispatches D-pad events to the TV Activity and injects touch gestures on mobile. Android's clock also advances during mobile exports so Coil crossfades finish. The renderer uses these native frames as moving display surfaces.

- TV: `app/src/test/java/com/example/NativeTvFilmCaptureTest.kt`. Native SDK 34 rendering at 1920 × 1080; Home, categories, movie rows, vertical movement, Details, episode components and player controls.
- Mobile: `../netflix-mobile/app/src/test/java/com/example/NativeMobileFilmMotionTest.kt`. Native SDK 34 rendering at 824 × 1790, and landscape player controls at 1790 × 824; Home swipe, Details, save state, profile editing, My List, Clips and Downloads.

Both harnesses are opt-in: regular test runs skip the export work unless `NETFLIXPRO_CAPTURE=1`. Fixture content and account state keep production exports deterministic without storing credentials. Playback uses an original procedural eclipse animation with the Kotlin player controls; it is not third-party movie footage or a live-stream performance test. The connecting ribbon expresses the shared product identity; it does not verify automatic device handoff.

The final composition is native 4K. Native UI and catalogue artwork retain their capture/source resolutions. The film is a cinematic visualization, not an emulator screen recording or a photorealistic 3D render.

## Reproduce in the cloud workspace

Keep the existing `/workspace/netflix-tv` and `/workspace/netflix-mobile` checkouts. The cloud Android environment supplies JDK 21, Gradle 8.9, SDK 34, Robolectric and native Skia. FFmpeg and Open Sans fonts are required. This workflow does not need an emulator or KVM.

```bash
source /workspace/cloud-setup/env.sh
cd /workspace/netflix-tv
NETFLIXPRO_CAPTURE=1 ./gradlew :app:testDebugUnitTest \
  --tests com.example.NativeTvFilmCaptureTest -Proborazzi.test.record=true
cd /workspace/netflix-mobile
NETFLIXPRO_CAPTURE=1 ./gradlew :app:testDebugUnitTest \
  --tests com.example.NativeMobileFilmMotionTest -Proborazzi.test.record=true

export PYTHONPATH=/workspace/cloud-setup/video-python:/workspace/cloud-setup/python-brand-tools
cd /workspace/netflix-tv/advertising-video/cinematic
python3 film.py prepare
python3 film.py audio
python3 film.py storyboard
python3 film.py preview --size 1920
python3 film.py render
python3 verify.py
```

`requirements.txt` lists the tested production dependencies. If the package index HTML is unavailable, `install_tools.py` downloads platform-matching wheels through PyPI's official JSON API, verifies their published SHA-256 values, then installs them locally with dependency resolution disabled. It preserves TLS and checksum verification.

`NETFLIXPRO_FILM_WORK` selects the renderer's asset/output folder; the Android harnesses currently export to `/workspace/artifacts/netflixpro-cinematic`. `NETFLIXPRO_FILM_THREADS=1` bounds OpenCV and encoder threads. On this four-core cloud machine, two independent scene processes are suitable; do not start an emulator during export.

`film.py scene --scene 8` renders an individual sequence. Completed scene metadata records resolution, frame count, duration and renderer signature; rerender a scene after changing its native assets. `film.py assemble` requires every sequence and adds 14 chapters, measured fixed-gain stereo mastering with a peak limiter, AAC and a smaller 1080p review file. The three-minute master fades to black at its final frame.

## Output

All generated frames, audio and video stay in `/workspace/artifacts/netflixpro-cinematic`, outside Git. The source project, shot list, capture harnesses, provenance and verification report are reviewable alongside the app work. The earlier film is preserved.

- `output/final/NetflixPro-Cinematic-TV-Mobile-4K.mp4`
- `output/final/NetflixPro-Cinematic-Review-1080p.mp4`
- `output/final/Verification.json`
- `output/previews/Native-Kotlin-Motion-Review.mp4`
- `output/previews/Cinematic-Storyboard.jpg`
- `assets/audio/music.wav`, `sound-design.wav`, `mix.wav`

The verification script checks streams, exact duration and frame count, every chapter boundary, decoded samples, a continuous decode pass, ending black frame and audio loudness. It does not claim playback-provider, cross-device synchronization or physical-TV performance validation.

## Verified delivery

The completed master is exactly 180 seconds: 5,400 frames at 3840 × 2160 / 30 fps, H.264 BT.709 with 48 kHz stereo AAC. A full decode passed and the final frame is black. Encoded audio measures −16.03 LUFS integrated and −1.61 dBTP; mastering preserves the quiet opening and musical build. See `verification.json` for the master checksum and `encoded-review.jpg` for samples decoded from the delivered film.

Native exports completed successfully: five TV tests and ten mobile tests, with no failures or skips when explicitly enabled. These are filmmaking exports, not a claim that all streaming providers or physical-device performance have been validated. Generated video is delivered separately because the 4K master exceeds GitHub’s ordinary Git file-size limit.
