# Playback exit and startup performance investigation

## Reproduced failure

The previous production player (version 29092055) exited completely during a signed-in Flash S1E5 resolution attempt on an Android TV API 28 emulator with 2 GiB RAM. The test copy retained the production DEX byte-for-byte; only its updater fixture and test signature differed. Android's low-memory killer recorded approximately 1.7 GiB resident memory before terminating the foreground app with signal 9. There was no Java fatal exception.

The captured resolver thread was inside Android ICU `Matcher.setInputImpl`, reached through Kotlin `Regex.findAll` in `PublicProviderIdentity.linkedHotstarIds`. The production DEX confirms the obfuscated method `c4.q2.b(String)` implements that function. Each next regex result creates/resets a matcher and copies the whole provider page into native memory. Pages with hundreds or thousands of links multiply that cost, outside the Java heap; coroutine timeouts do not interrupt a synchronous regex scan.

## Fix

`TextMatchScanner.scanMatches` reuses one Java `Matcher.find()` cursor and detaches the matched groups. Provider-page, embedded-script, manifest-token and trailer-page scans use it. Host, title, season, signature and expiry validation remain in their existing parsers.

The earlier compatibility workaround is reverted: the player uses its original track selection and TextureView path. Subscription resolution limits remain. The removed strict renderer/video constraints could discard the available video rendition while retaining audio.

Launcher fallback movies now use an unknown year until metadata resolves it. Previously they inherited the model's placeholder 2024 year, which could send an older title through the wrong identity searches.

## Android allocation comparison

Both runs scanned the same synthetic 481,742-character page: 512 unrelated HTTPS links and one valid Hotstar identity. The real APK parser ran in an isolated Android process, without an account.

| Measurement | Previous production parser | Fixed optimized test APK |
| --- | ---: | ---: |
| Native allocation growth | 527,703 KiB | 1,045 KiB |
| Scan time on this emulator | 27,017 ms | 1,091 ms |
| Parsed identity | Correct | Correct |

The fixed parser passes a 16 MiB allocation budget. The signed-in fixed app also completed a resolution attempt with approximately 47 MiB native heap and under 200 MiB observed resident memory, without the process exit. That intermediate attempt still used the old launcher placeholder year; final playback checks must use the corrected launcher build or a title selected from the catalogue.

Reproduce the allocation check with [the Android audit](../tools/playback-memory-audit/README.md). A Node/JVM-only parser test cannot reproduce Android ICU's native allocation behavior.

## Startup and diagnostics changes

- Profile selection waits for admission and profiles, while catalogue requests continue in the background.
- Home receives its first useful feed without waiting for the slowest of four initial requests. Request/parsing concurrency stays bounded, and the complete initial batch retains its normal order.
- A 20 MiB HTTP cache honours TMDB freshness headers across app starts; stalled catalogue calls have an overall timeout.
- Optional artwork warming waits until Home has rendered and input is idle.
- Diagnostics separate row assembly from network/intentional idle waits. Production stage timings include Java/native heap readings.
- Timestamp formatting is synchronized across logging threads. Log viewing retains only the requested tail in memory.

Emulator frame times include software CPU/GPU emulation and first-run compilation; they are not physical-TV FPS measurements. The production release uses version code 29092057 (17.11.2026.2); deployment verification is recorded separately.
