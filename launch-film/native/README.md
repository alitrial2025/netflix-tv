# Kotlin screen exports

The film uses newly rendered production Jetpack Compose screens. It does not crop existing app screenshots or recreate the UI in HTML/SVG. Robolectric native Skia and Roborazzi execute the shipped TV HomeScreen and mobile HomeScreen, NetflixTopBar, NetflixBottomNav and DetailScreen. The mobile detail entry uses the same slide/fade transitions as MainActivity.

The generic Home profile, subscription state and viewing progress are synthetic. Existing, checked-in catalogue artwork is loaded from local fixtures. No login, payment service, CDN playback, release signing credentials or real user records are used.

The tests in this directory are templates installed only into GitHub Actions checkouts by `scripts/generate-native-assets.py --prepare`. They opt in with `NPRO_NATIVE_CAPTURE=1` and use configurable `NPRO_NATIVE_FIXTURES` and `NPRO_NATIVE_OUTPUT` paths.

## Cloud commands

Check out the TV repository and `alitrial2025/netflix-mobile`, set up Java 21/Android 35 and Gradle, then run from the TV root:

```sh
python launch-film/scripts/generate-native-assets.py --prepare \
  --mobile-root "$GITHUB_WORKSPACE/mobile" --github-env "$GITHUB_ENV"
```

The environment file applies to later workflow steps. Run in the respective checkout roots:

```sh
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest \
  --tests 'com.example.LaunchFilmTvCaptureTest' -Proborazzi.test.record=true
```

```sh
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest \
  --tests 'com.example.LaunchFilmMobileCaptureTest' -Proborazzi.test.record=true
```

Finish from the TV root with:

```sh
python launch-film/scripts/generate-native-assets.py --verify
```

This compiles test/debug Kotlin in GitHub Actions. It does not build release APKs or build locally.

## Output contract

All paths below are relative to `launch-film/public`. Each sequence is 60 PNG frames, four seconds at 15 fps, numbered `00000.png` through `00059.png`. Three.js selects each native frame at 15 fps while the surrounding animation renders at 30 fps.

| Sequence | Native size | Action |
| --- | --- | --- |
| `screens/tv-home/` | 2560×1440 (1280×720 dp, xhdpi) | Real DPAD movement from Home into categories and poster rails |
| `screens/mobile-home/` | 1236×2685 (412×895 dp, xxhdpi) | Compose lazy-list animated browse and return to hero |
| `screens/mobile-details/` | 1236×2685 | Native slide/fade entry to details, then scroll into episodes |

Still frames are `screens/tv-home.png`, `screens/mobile-home.png`, `screens/mobile-details.png`. `screens/manifest.json` records actual dimensions, source revisions, hashes, motion variation and provenance. Verification fails on missing frames, wrong dimensions, invalid PNG data or unchanged output.
