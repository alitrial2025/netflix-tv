# Home entry crash

## Evidence

The TV at `10.129.131.14:5555` recorded a main-thread crash in
`com.netflixprotv.apk` at 22:48:35 on 2026-09-28:

`java.lang.VerifyError: Verifier rejected class com.example.ui.screens.HomeScreenKt`

The verifier rejects the generated `HomeScreen` method when copying the
Compose `Composer` argument from register `v256`. The method is rejected
before Home's content executes. This crash is not an out-of-memory exception
or a DirectCDN request failure. The selected log lines are saved beside this
document in `home-entry-crash-2026-09-28.txt`.

## Source change

The previous Home composable was 1,115 lines and contained its state setup,
navigation, backdrop, entire browsing tree and profile transition. It now
delegates rendering to separate composables:

- Home state/setup and effects: 419 lines.
- Scene container: 62 lines.
- Kids backdrop: 57 lines.
- Navigation bar: 58 lines.
- Browse content: 443 lines.
- Kids profile transition: 154 lines.

`HomeRenderScope` passes the original State objects, focus requesters, catalog
inputs and motion State to those sections. The scroll column, row alpha rules,
stationary selection ring, hero geometry and Kids-only backdrop condition are
preserved. Separating the generated methods reduces the compiler's register
allocation pressure in the function identified by the crash log.

## Verification

Seven source checks passed: browse rendering, Kids backdrop rendering,
navigation rendering and Kids profile transition match the saved original;
the adult screen retains the Kids-only backdrop guard; the shared mutable
focus/readiness state is retained; and the source syntax parses cleanly.
Results are in `home-entry-source-checks-2026-09-28.json`.

The original Home source is backed up in
`home-entry-before-split-2026-09-28.zip`. No build, Kotlin compilation or APK
installation was performed. Source checks cannot verify the generated DEX.
Build a new release on another machine and test adult/Kids Home entry on this
Android 11 TV, then navigation between hero, categories, rows and Search.
The installed APK still contains the rejected method until it is replaced.
