# Android playback parser memory audit

With an Android TV emulator or development TV connected through ADB:

```sh
./gradlew :app:assembleDebug
node tools/playback-memory-audit/audit-android.mjs --apk=app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` and put the JDK's `javac` on `PATH`. Use `--serial=<adb serial>` for another device. The APK is loaded in an isolated Android process; it is not installed and no account is needed. A release APK needs the corresponding unobfuscated class/method, or explicit `--class` and `--method` names from its mapping.

The synthetic 470 KiB page contains 512 unrelated HTTPS links followed by one valid Hotstar identity. The audit checks the identity result and a 16 MiB native-allocation budget. It removes its temporary files afterward.

Run this on Android: JVM/Node regex engines cannot reproduce Android ICU's repeated full-input native copies. The previous production APK used over 500 MiB on this fixture despite its modest Java heap.
