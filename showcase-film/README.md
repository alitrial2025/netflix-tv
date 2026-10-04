# NetflixPro — TV and phone showcase

One fully edited three-minute film for both released apps. This is a 1920×1080, 30 fps product tour with 29 chapters, native Kotlin UI, perspective device shots, camera pushes, focus pulls, travelling-light reveals, matched transitions, original music and stereo sound effects. The final MP4 is ready to watch; editing software is not required.

DaVinci Resolve is a desktop editor and is not installed in GitHub or this cloud environment. The deliverable includes an optional FCP7 XML interchange timeline with media, original audio and captions for import into Resolve. It is not a proprietary `.drp` project, and an actual Resolve application import cannot be verified in this environment. Each main scene is a separate editable clip; its internal visual treatment is baked into that clip.

## Screen coverage

TV: profiles and setup, billboard and browsing rails, category, search, title details, episode selection, recommendations, extras, extended information, playback controls, audio/subtitles, profile editing, ambient display, authentication and membership.

Phone: Home browsing and saving, search, New & Hot, games, Clips, title details, landscape playback, audio/subtitles, episode drawer, post-play, profile picker/edit/setup/icons, downloads, offline state, Smart Downloads, upgrade prompt, My Netflix, pairing, casting, settings, notifications, authentication and membership plan cards including scrolled content.

Screens execute production Compose code through Robolectric native Skia. Account state and fixture data are synthetic. The TV harness directs Firebase clients to local, unused emulator ports and uses a capture-only shadow for a previously verified Premium session. The shadow and harnesses never enter release APKs. Original procedural eclipse footage illustrates the player; no real payment, customer account or stream-speed measurement is used. Film coverage emphasizes user-facing screens and principal controls; it does not claim to display every internal loading/error or legacy duplicate component.

## Reproduce

Keep TV and mobile repositories beside each other, named `netflix-tv` and `netflix-mobile`. Install JDK 21, Android 35, Gradle 8.9, FFmpeg, Open Sans and the existing cinematic Python requirements. Capture outputs use `/workspace/artifacts/showcase-20261004` by default.

```sh
python showcase-film/scripts/capture.py install
NETFLIXPRO_CAPTURE=1 ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest \
  --tests com.example.ShowcaseTvCaptureTest -Proborazzi.test.record=true
cd ../netflix-mobile
NETFLIXPRO_CAPTURE=1 ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest \
  --tests com.example.ShowcaseMobileCaptureTest -Proborazzi.test.record=true
cd ../netflix-tv
python showcase-film/scripts/capture.py remove
python showcase-film/render.py audio
python showcase-film/render.py render
python showcase-film/scripts/deliver.py timeline
python showcase-film/scripts/deliver.py verify
```

Native interaction sequences are exported at 15 fps; the composition and delivery are 30 fps. The renderer uses two CPU processes, and each scene is resumable. Generated video, screenshots and audio stay outside Git; the source edit and capture templates are versioned here. Perspective graphics and the base original score reuse `advertising-video/cinematic` primitives.

## Delivery

- `output/NetflixPro-TV-and-Phone-3min.mp4`
- `output/NetflixPro-Resolve-Project.zip`
- `output/NetflixPro-Captions.srt`
- `output/Verification.json`
- `output/native-provenance.json`
- `output/review/Storyboard.jpg`

`deliver.py verify` checks dimensions, frame count, exact duration, stereo audio, chapter count, continuous decoding and the interchange timeline's clip boundaries. A decoded contact sheet and audio loudness report supplement those checks. Actual Resolve import remains untested.
