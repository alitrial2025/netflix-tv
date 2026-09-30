# HomeScreen UI Refinement Audit

Refinement pass through the home screen composition: **TopNavBar → Billboard → CategoriesBar → NetflixMovieRow carousel → Kids hero branch**.

This is a polish/refinement audit, not a feature list. Items are grouped by area with concrete `file:line` pointers. Suggestions are ordered by visual impact within each section; the final "Quick Win Priority" picks the top 5.

---

## 1. TopNavBar — Profile Dropdown (`TopNavBar.kt`)

### a. The "kids" pill is visually weaker than the rest of the chrome
`TopNavBar.kt:981-995` renders a flat white pill with red text where the `N` logo normally goes. The pill is the same height as the nav row, so it crowds the tabs and breaks the visual rhythm.

- Reduce horizontal padding to `6.dp` and vertical to `1.5.dp`, and round the corners tighter (`RoundedCornerShape(8.dp)`) so it reads as a **badge**, not a button.
- Add a 1dp amber border (`Color(0xFFFF9D2B).copy(alpha = 0.6f)`) to harmonize with the KIDS badge used in profile select (`HomeScreen.kt:1132`).

```kotlin
Box(
    modifier = Modifier
        .background(Color.White, RoundedCornerShape(8.dp))
        .border(1.dp, Color(0xFFFF9D2B).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
        .padding(horizontal = 6.dp, vertical = 1.5.dp)
) {
    Text("kids", color = NetflixRed, fontSize = 12.sp, fontWeight = FontWeight.Black)
}
```

```
preview:
  before:                  after:
  | N  Home Series Films | | N  Home Series Films | [kids] |
                            tighter, amber-bordered badge
```

### b. The "selected" dot under tab labels is invisible on the focused (white-pill) state
`TopNavBar.kt:851-859` (search icon) and `:964-972` (tab labels) render the white dot only when `isSelected && !isTabFocused`. When the tab is focused, the white pill sits behind the label and the dot disappears into it. Current behavior is correct, but verify visually: the dot must never overlap the white pill, or the "you are here" signal is lost.

- Either keep the existing condition and accept the trade, or shift the dot down `1.dp` so it always peeks below the pill on focus.
- Consider: only render the dot when `!isTabFocused` AND lift it `0.5.dp` further from the label baseline (current `top = 1.5.dp` padding).

### c. The dropdown's sliding white pill lag
`TopNavBar.kt:309-323` animates the white pill at 180ms, but the dropdown column itself has no entrance animation. When the dropdown opens, items render instantly and the pill slides in over them — jarring.

- Add a `fadeIn(180)` + slight `scaleIn(0.98f → 1f)` to the dropdown Box (`:325-336`).
- Reduce the pill tween to `150ms` so it lands in sync with the dropdown fade.

```kotlin
AnimatedVisibility(
    visible = isDropdownExpanded,
    enter = fadeIn(180) + scaleIn(initialScale = 0.98f, animationSpec = tween(180)),
    exit  = fadeOut(120) + scaleOut(targetScale = 0.98f, animationSpec = tween(120))
) {
    Box(modifier = Modifier.width(250.dp).clip(RoundedCornerShape(18.dp))...) { ... }
}
```

### d. Tab text baseline jumps between focused / un-focused
`TopNavBar.kt:961` toggles `FontWeight.Bold` ↔ `Medium`, and the white-pill `scale(1.04f)` causes BOLD text inside the pill to render slightly clipped at the top. The current state is fine on 1080p but tight on 720p TV.

- Keep weight constant (`FontWeight.SemiBold`) and use color + pill only for focus state.
- Or compensate: `letterSpacing = (-0.2).sp` when `isTabFocused` so BOLD glyphs don't push against the pill's clip.

```kotlin
Text(
    text = tabName,
    color = if (isTabFocused) Color.Black else if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
    fontSize = 12.5.sp,
    fontWeight = FontWeight.SemiBold,           // constant weight
    letterSpacing = if (isTabFocused) (-0.2).sp else 0.sp
)
```

```
preview:
  before (focused):  (  Home  )  pill clips top of "H" by ~1px on 720p
  after  (focused):  (  Home  )  letter-spacing tightens, glyphs sit flush
```

---

## 2. Billboard — Hero Section (`Billboard.kt`)

### a. The 4K/HDR/5.1 badges are missing on the Billboard
`NetflixMovieRow.kt:262-296` ships a "PRO" + "4K HDR" chip combo on row cards, but the Billboard (the most valuable real estate — the user is literally deciding whether to play) has nothing. Add a small chip row under the metadata, mirroring the row's chip styling. Hardcode the conditions; no TMDB data needed.

```kotlin
// Inside the bottom-left Column, just after the metadata Row (~:510)
AnimatedVisibility(visible = isBillboardFocused) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp)
    ) {
        if (movie.rating != "18") QualityChip("HD")
        if (movie.duration.contains("2h", ignoreCase = true).not()) QualityChip("4K")
        QualityChip("HDR")
        QualityChip("5.1")
    }
}
```

```
preview:
  before:  [ Series • 2024 • 2h 14m • [18] ]
  after:   [ Series • 2024 • 2h 14m • [18] ]
           [ HD  4K  HDR  5.1 ]
```

### b. Description has no "..." affordance for long copy
`Billboard.kt:438-450` truncates with `maxLines = 2` and `TextOverflow.Ellipsis`, but there's no visual hint that more exists. Netflix solves this with a 40dp horizontal fade overlay on the right edge of the description block.

```kotlin
// Replace Modifier.padding(top = 4.dp, end = 24.dp) at :447
Box {
    Text(
        text = targetMovie.description,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp)
    )
    Box(Modifier.matchParentSize()
        .background(Brush.horizontalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
            startX = 0f, endX = 600f
        )))
}
```

```
preview:
  before:  A rogue cop investigates a series of               |
  after:   A rogue cop investigates a series of               ▓▓
           murders in a coastal town... [fade → dark]
```

### c. Title and metadata have inconsistent slide offsets
`Billboard.kt:377, 430, 461` use three different `initialOffsetX` values (0.10, 0.06, 0.08). When a movie changes, the three lines stagger in at different speeds — it reads as accidental, not choreographed. Standardize on one offset.

```kotlin
// Define once, reuse in all three AnimatedContent transitionSpec blocks
private val BillboardOffset = tween<Float>(280, easing = FastOutSlowInEasing)
private const val SLIDE_FRACTION = 0.08f

// Then: initialOffsetX = { fullWidth -> (fullWidth * SLIDE_FRACTION).toInt() }
```

```
preview:
  before:  TITLE ───→ 0.10 slide (280ms)
           metadata ─→ 0.06 slide (260ms)  ← feels late
           year     ──→ 0.08 slide (260ms)  ← feels early
  after:   TITLE ────→ 0.08 slide (280ms)
           metadata ─→ 0.08 slide (280ms)  ← in sync
           year     ──→ 0.08 slide (280ms)
```

### d. The play-button text "Upgrade Plan to Unlock" overflows the pill on small TVs
`Billboard.kt:527-531` + `:657-663` puts a 17sp "Upgrade Plan to Unlock" inside a 10dp-vertical-padded pill. On a 32" 720p TV the text measures ~280px and visually crushes the lock icon. The lock icon already carries the meaning — shorten the label.

```kotlin
val buttonText = when {
    !isLoggedIn -> "Play Trailer"
    isLocked -> "Unlock"             // was: "Upgrade Plan to Unlock"
    else -> "Play"
}
```

```
preview:
  before:  [ 🔒 Upgrade Plan to Unlock ]   ← ~280px on 720p
  after:   [ 🔒 Unlock ]                   ← ~110px, icon gets room
```

### e. Top-left N logo and title column compete for visual space
`Billboard.kt:351-361` pins the N at `start = 28, top = 20` with full opacity. The title column starts at `start = 36, bottom = 22` (`:367`). On cards with a short title logo (~60dp tall), the N and the title visually merge into one stack and the title loses primacy. Subdue the N when the billboard is focused.

```kotlin
// On the N Image (~:351), derive alpha from focus state
val nAlpha by animateFloatAsState(
    targetValue = if (isBillboardFocused) 0.6f else 1f,
    animationSpec = tween(200),
    label = "nAlpha"
)
Image(
    painter = painterResource(R.drawable.ic_netflix_n),
    modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 28.dp, top = 20.dp)
        .width(22.dp).height(38.dp)
        .graphicsLayer { alpha = nAlpha }   // ← animate, don't snap
        .shadow(12.dp, spotColor = Color.Black)
        .zIndex(10f)
)
```

```
preview:
  before:  N │                          ← N + title = one stack
           │ THE        ← visually merged
           │ TITLE
  after:   N │ THE      ← N is dim, title wins focus
             (faded)
             │ TITLE
```

### f. Subtitle text positioning is fragile
`Billboard.kt:565-583` drops subtitle text at `BottomEnd, end = 40, bottom = 40`. The buttons live at `BottomStart, bottom = 22` (`:521-560`). On widescreen posters with a long caption and on TV screens where the button row is centered, the subtitle clips behind or overlaps the More Info button. Only show subtitles when the buttons are hidden (i.e. not focused, or preview-only).

```kotlin
// Replace the unconditional subtitle Text (~:566) with:
if (currentSubtitleText.isNotBlank() && !isBillboardFocused) {
    Text(
        text = currentSubtitleText,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.End,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 40.dp, bottom = 40.dp)
            .widthIn(max = 600.dp)
            .graphicsLayer { shadow(...) }
    )
}
```

```
preview:
  before:  [▶ Play] [ⓘ More Info]    "I'm the ghost of..."
                                          ↑ overlaps button
  after:   [▶ Play] [ⓘ More Info]    (subtitle hidden while focused)
           ───── unfocused only ─────
                                       "I'm the ghost of..."
```

---

## 3. Categories Bar (`CategoriesBar.kt`)

### a. The chip color system doesn't match the ambient mood
`CategoriesBar.kt:325` picks between a fixed `NormalChipGradient` and `FocusedChipGradient` for every category, but `getCategoryThemeColor()` (`:85-101`) already maps each category to a hue — and `moodColor` is even passed down to `CategoryChipItem` (`:280`) but never read. The chips are missing the chance to subtly preview the category's mood (red tint for Action, blue for Sci-Fi, etc.).

- Overlay `0.15f` of `moodColor` on the focused chip's gradient stop.
- On unfocused chips, a barely-visible (5% alpha) tint of the same color so the row reads as a category palette at a glance.

```kotlin
val tint = moodColor
val currentColors = if (isFocused) {
    FocusedChipGradient.map { it.blend(tint, 0.15f) }   // e.g. Action → red lean
} else {
    NormalChipGradient.map { it.blend(tint, 0.05f) }
}
```

```
preview:
  Action chip focused    Sci-Fi chip focused    Drama chip focused
  [  Action  ]  red     [  Sci-Fi  ]  cyan     [  Drama  ]  crimson
  tint ≈ 15% alpha      tint ≈ 15% alpha       tint ≈ 15% alpha
```

### b. Active category indicator is invisible
When the user navigates left/right, the focused chip is white-outlined but the page does not navigate to that category (CENTER = navigate, focus = preview color). There is no indicator of "this is what's currently loaded." All chips currently render at full white text weight, so the row reads as a horizontal list of equal-weight items.

- Add a tiny `•` or animated dot under the focused chip title (`CategoriesBar.kt:369-378`).
- Or: animate unfocused chip text from `Color.White` to `Color.White.copy(alpha = 0.6f)` on focus, so the active one is obviously brighter.

```kotlin
Text(
    text = title,
    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.6f),
    fontSize = if (isFocused || isExpanded) 20.sp else 18.sp,
    fontWeight = FontWeight.Bold
)
if (isFocused) {
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
        .size(4.dp).background(Color.White, CircleShape))
}
```

### c. Chip height vs touch target
`CategoriesBar.kt:176` sets `chipHeight = 84.dp`. Netflix's TV chips are typically 64–72dp. At 84dp the row feels chunky next to the 435dp billboard above and 395dp rows below.

- Try `72.dp` to give the billboard more breathing room and bring the row into Netflix's 16–18% ratio (see §6c).

```kotlin
val chipHeight = 72.dp   // was 84.dp
```

```
preview:
  before:  [Dramas] [Comedies] [Action]   row feels chunky, ~27% of billboard height
  after:   [Dramas][Comedies][Action]    row reads as a thin rail, ~16%
```

### d. Chip text doesn't vertically align during animation
During the 700ms slide-in (`CategoriesBar.kt:144-156`), only the chip box moves; the text inside has no transition. When a chip becomes focused, the text jumps from 18sp to 20sp (`:372`) with no animation, causing a visible "pop."

- Wrap the `Text` in `AnimatedContent` with a 200ms scale + size tween so the size change is interpolated, not snapped.

```kotlin
AnimatedContent(
    targetState = if (isFocused || isExpanded) 20.sp else 18.sp,
    transitionSpec = { tween(200, FastOutSlowInEasing) },
    label = "chipTextSize"
) { size ->
    Text(text = title, color = Color.White, fontSize = size, fontWeight = FontWeight.Bold)
}
```

```
preview:
  before:  18sp text → (focus) → 20sp text   instant pop, ~1 frame
  after:   18sp text → (focus) → 20sp text   eased scale-up over 200ms
```

---

## 4. Movie Rows (`NetflixMovieRow.kt`)

### a. The "4K HDR" pill is a hardcoded lie
`NetflixMovieRow.kt:285-296` always renders "4K HDR" on Continue Watching and Netflix Pro rows. The `Movie` model has no quality field, so a 2h documentary and a flagship HDR series get the same badge. Drive the chips from a `quality: List<QualityTag>` field so they surface what the title actually has (`HD`, `5.1`, `CC`, `HDR`, `Atmos`).

```kotlin
// Movie.kt — extend the model
data class Movie(
    // ...existing fields
    val quality: List<QualityTag> = emptyList(), // empty = render nothing
)

enum class QualityTag(val label: String) {
    HD("HD"), FourK("4K"), FiveOne("5.1"), CC("CC"), HDR("HDR"), Atmos("ATMOS")
}

// NetflixMovieRow.kt — replace the hardcoded 4K HDR Box (was :285-296)
movie.quality.forEach { tag ->
    Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), BadgeShape3)
        .padding(horizontal = 5.dp, vertical = 1.5.dp)) {
        Text(tag.label, color = Color.White.copy(alpha = 0.8f),
            fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
    }
}
```

```
preview:
before:                          after:
Continue Watching [PRO][4K HDR]  Continue Watching [PRO][HD][5.1][CC]
[card][card][card][card]         [card][card][card][card]
  ^^^^^^^^                        ^^^^^^^^^^^^^^^^^^^
  "4K HDR" for every title        per-movie: HD / 5.1 / CC / HDR
```

### b. The metadata bar clips off the bottom of row #1+
`NetflixMovieRow.kt:520-523` hardcodes `.height(72.dp)` for the metadata block. On tabs without a billboard (e.g. My Netflix), the block is fine; but as the user scrolls down and row #2 gains focus, the row slides to the top of the viewport and the 72dp metadata falls below the visible scroll window. Add `bottom = 8.dp` to the existing padding so the last line of the description stays in frame.

```kotlin
// NetflixMovieRow.kt:519-522
modifier = Modifier
    .fillMaxWidth()
    .height(72.dp)
    .padding(start = 40.dp, top = 6.dp, end = 40.dp, bottom = 8.dp), // +8.dp buffer
```

```
preview:
before:                          after:
+----------------------+         +----------------------+
| [card][card][expnd]  |         | [card][card][expnd]  |
+----------------------+         |                      |
| [meta clipped here]  |         | Series • 2025 • 1h   |
| ...                  |         | Long description...  |
+----------------------+         +----------------------+
   ^^^^^^                          bottom 8.dp keeps the
   last line of desc               description line in
   scrolls under the focus ring    the visible window
```

### c. Top 10 rank number sits on top of the lock badge
`NetflixMovieRow.kt:684-732`: the red "TOP 10" pill defaults to `TopEnd`; the lock badge mirrors that. `:707` correctly swaps the lock to `TopStart` when both are present, but then the giant rank number (`Top10RankNumber`, `:781`) anchors at `BottomStart` with `y = 10.dp` and crops the lower edge of the lock badge. Shift the rank down by ~12dp when `isLocked && rank != null` so the two never touch.

```kotlin
// NetflixMovieRow.kt:781-787
Top10RankNumber(
    rank = rank,
    modifier = Modifier
        .align(Alignment.BottomStart)
        .offset(
            x = (-4).dp,
            y = if (isLocked && rank != null) 22.dp else 10.dp
        )
        .zIndex(4f)
)
```

```
preview:
before (locked + top 10):        after:
+--------------------+           +--------------------+
|[LOCK]    [TOP 10]  |           |[LOCK]              |
|                    |           |                    |
|  1                 |           |                    |
| [poster]           |           |  1                 |
|                    |           | [poster]           |
+--------------------+           +--------------------+
  ^^^^^                              ^^^
  "1" overlaps the                  "1" shifted down 12dp,
  bottom of [LOCK]                  no collision
```

### d. The "Reminded" badge is washed out on dim cards
`NetflixMovieRow.kt:840-880`: when `isReminded`, the badge background flips to `NetflixRed` but the border collapses to `0.dp` / `Transparent` (`:855-859`). On an unfocused row the whole card is partially transparent, so the red looks like a soft pink and the white bell gets lost. Give the reminded state a thicker outer ring so it pops regardless of card alpha.

```kotlin
// NetflixMovieRow.kt:855-859
.border(
    width = if (isReminded) 1.5.dp else 1.dp,
    color = Color.White.copy(alpha = 0.6f),  // always a ring; thicker for reminded
    shape = BadgeShape12
)
```

```
preview:
before:                          after:
  ┌─────────────┐                  ╔═════════════╗
  │🔔 Reminded  │                  ║🔔 Reminded  ║
  └─────────────┘                  ╚═════════════╝
   dim, blends w/ card             red + 1.5dp white ring
   at 0.45f row alpha              stays visible at any alpha
```

### e. The progress bar is too thin on portrait cards
`NetflixMovieRow.kt:883-898` renders a flat `5.dp` bar. It reads cleanly on the 437dp expanded card but disappears on the 190dp portrait thumbnail. Scale the bar height with the card width so the visual weight is consistent across both states.

```kotlin
// NetflixMovieRow.kt:883-888
val progressHeight = (cardWidth.value * 0.025f).coerceIn(3f, 8f).dp
Box(
    modifier = Modifier
        .fillMaxWidth()
        .height(progressHeight)                    // 4.75dp on 190dp, 10.9dp on 437dp
        .background(Color.White.copy(alpha = 0.25f))
        .align(Alignment.BottomCenter)
) { /* progress fill at :891-897 unchanged */ }
```

```
preview:
before (190dp portrait):         after (190dp portrait):
  [============     ]              [====================]
       ^^^^^                          ^^^^^^^^
       5dp looks like a               4.75dp scaled,
       sub-pixel hairline             visible at any zoom

before (437dp expanded):          after (437dp expanded):
  [============================]     [============================]
       ^^^^^^^^^^                        ^^^^^^^^^^^^^
       5dp is fine, but                  10.9dp scales with the
       inconsistent with portrait         hero card without crowding
```

### f. The expanded title logo pops in after ~80ms of empty space
The original audit pointed at `NetflixMovieRow.kt:735-766` for this issue, but that range is actually the N+RESUME Row (see 4g). The real title gate is at `NetflixMovieRow.kt:793-813`: `if (expandedProgress > 0.05f)` plus `.graphicsLayer { alpha = expandedProgress }` (`:810`). The card is in focus for a few frames before the gate trips, so the hero card looks empty until the logo suddenly appears. Pre-render the `AsyncImage` at all widths with `alpha = 0` and let the existing `expandedProgress` drive the fade — no gate, no pop.

```kotlin
// NetflixMovieRow.kt:793-813 (the title gate — 4f was originally anchored to 735-766, which is the N+RESUME row)
if (!dynamicLogoUrl.isNullOrBlank()) {
    AsyncImage(
        model = remember(dynamicLogoUrl) { /* ImageRequest */ },
        contentDescription = movie.title,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = titlePaddingStart, end = 12.dp, bottom = if (progress != null && progress > 0f) 20.dp else 12.dp)
            .height(42.dp)
            .widthIn(max = 210.dp)
            .graphicsLayer { alpha = expandedProgress },  // already fades; gate is removed
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart
    )
}
```

```
preview:
before:                          after:
focus:  [empty card]             focus:  [card logo (α=0.05)]
frame 5: [card + logo POPS]      frame 5: [card logo (α=0.4)]
                                       frame 9: [card logo (α=0.9)]
   ^^^^                              ^^^^^^^^^^^^^
   ~80ms "empty" hero               no pop, smooth fade
```

### g. The N logo and RESUME pill crowd the expanded card's top edge
`NetflixMovieRow.kt:735-766` (the actual N+RESUME row that 4f was originally pointing at) renders the N (`painterResource(R.drawable.ic_netflix_n)`, 14×24dp) and the RESUME pill side-by-side with a 6dp gap. That's ~46dp of chrome on a card whose primary content (the logo) lives at the bottom. Drop the standalone N and roll it into the pill itself: a single "N · RESUME" badge keeps the meaning and reclaims ~24dp of vertical headroom for the title.

```kotlin
// NetflixMovieRow.kt:751-765 — merge N into the RESUME pill
if (progress != null && progress > 0f) {
    Box(
        modifier = Modifier
            .background(NetflixRed, BadgeShape3)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "N · RESUME",                  // single combined chip
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp
        )
    }
}
// Drop the standalone Image(painterResource(R.drawable.ic_netflix_n)) at :744-750
```

```
preview:
before:                          after:
+--+ +--------+                  +-------------------+
|N | | RESUME |                  | N · RESUME        |
+--+ +--------+                  +-------------------+
  ^    ^                              ^
  N icon (24dp) + 6dp gap          single chip
  + pill (~30dp) ≈ 60dp total      ≈ 30dp total,
  eats 60dp of top space           30dp reclaimed for
  before the title can render      the title/logo
```

---

## 5. HomeScreen Composition (`HomeScreen.kt`)

### a. The 5dp spacer between TopNavBar and Billboard reads as a seam in dark mood
`HomeScreen.kt:598` `Spacer(modifier = Modifier.height(5.dp))` sits between the nav bar and the Billboard. On non-kid, non-search tabs the Billboard is drawn over a dark `moodColor` (palette-extracted hero color) and the 5dp gap is exactly the height of the Billboard's `RoundedCornerShape(20.dp)` bottom clip — so the gap reads as a visual seam, not breathing room. Bump to 10dp and let the rounded bottom corners feel like a margin, not a cut.

```kotlin
// HomeScreen.kt ~598
// before
Spacer(modifier = Modifier.height(5.dp))
// after
Spacer(modifier = Modifier.height(10.dp))
```

```
preview:  before                              after
  +----------------------+                +----------------------+
  | TopNavBar            |                | TopNavBar            |
  +----------------------+                +----------------------+
  | <- 5dp dark seam ->  |                | <- 10dp breathing -> |
  |  +----------------+  |                |  +----------------+  |
  |  |  Billboard     |  |                |  |  Billboard     |  |
  |  |  (rounded 20)  |  |                |  |  (rounded 20)  |  |
  |  +----------------+  |                |  +----------------+  |
```

### b. The "entering kid profile" overlay freezes the screen for 1.4s
`HomeScreen.kt:1065-1199` paints an opaque `NetflixBlack` Box with a 420dp radial amber glow, a 145dp avatar card, the kid's name and a `NetflixSpinner` for `delay(1400)` (`:1070`). The user has already committed to the kid profile — the freeze reads as a hang, not a transition. Three small changes turn it into a graceful cross-fade:

- Drop total duration from 1400ms to 900ms.
- Lower overlay opacity to `0.88f` (`:1094-1095`) so the destination Kids hero is visible underneath — kids already know the destination.
- Replace the pinwheel spinner with a thin `LinearProgressIndicator` + "Loading Kids Profile…" label so the wait is narratable.

```kotlin
// HomeScreen.kt ~1091-1199
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(NetflixBlack.copy(alpha = 0.88f))   // was 1.0f
        .graphicsLayer { alpha = overlayAlpha }
        .zIndex(700f),
    contentAlignment = Alignment.Center
) {
    /* avatar + glow as before */
    Text("Loading Kids Profile…", color = Color.White, fontSize = 16.sp)
    LinearProgressIndicator(
        modifier = Modifier.width(220.dp).padding(top = 4.dp),
        color = Color(0xFFFF9D2B)
    )
}
```

### c. The focus state machine uses raw integer indices with a wrap-to-nav bug
`HomeScreen.kt:164` `currentFocusLevelState` is an `IntState` with the mapping `(-2) nav, (-1) billboard, 0 categories (Home only), 1..N rows` documented in comments at `:159-163`. It works, but the magic numbers are a footgun. Worse, `HomeScreen.kt:1014` calls `requestNavBarFocus()` when the user presses DOWN on the last row — Netflix doesn't wrap upward on TV, and the surprise focus jump is jarring after the user has scrolled deep into a category. Two changes:

- Replace `Int` with a sealed class so the focus target is self-describing and `coerceIn` math disappears.
- On DOWN from the last row, drop focus rather than jumping — a "you've reached the end" affordance, not a teleport.

```kotlin
sealed class FocusTarget {
    object Nav : FocusTarget()
    object Billboard : FocusTarget()
    object Categories : FocusTarget()
    data class Row(val index: Int) : FocusTarget()
    object EndOfContent : FocusTarget()        // replaces the wrap-to-nav bug
}

val currentFocus = remember { mutableStateOf<FocusTarget>(FocusTarget.Nav) }
// In NetflixMovieRow onDpadDown, last row:
onDpadDown = {
    if (index < activeDisplayedCategoryRows.size - 1) {
        currentFocus.value = FocusTarget.Row(index + 1)
        /* requestFocus(...) */
    } else {
        currentFocus.value = FocusTarget.EndOfContent
    }
    true
}
```

### d. The "Continue Watching" / "Netflix Pro" PRO/4K badge is hardcoded by string match
`NetflixMovieRow.kt:241` checks both `"Continue Watching"` and `"Netflix Pro"` for the PRO + 4K HDR badge combo. A third Pro-branded row would silently miss the badge, and renaming either existing row title would also miss it. Promote the badge to a data field on the row tuple — `Row(title, movies, badge: RowBadge?)` — so the badge is rendered from data, not from string equality.

```kotlin
enum class RowBadge { Pro, New, Top10, None }

data class HomeRow(
    val title: String,
    val movies: List<Movie>,
    val badge: RowBadge = RowBadge.None
)

// NetflixMovieRow.kt, replace the if-block
val showProBadge = row.badge == RowBadge.Pro
```

### e. The "row alpha" cascade makes inactive rows look broken (0.45f is too dim)
`HomeScreen.kt:954-962` cascades row alpha: `1.0f` active, `0.45f` off-focus (downstream rows), `0f` exiting north-west. On a TV at couch distance `0.45f` reads as "this row is disabled" — the choreography wants "ambient but visible." Lift off-focus to `0.7f` so the row feels parked, not broken; the `0f` exit is the actual visual cue for the north-west fly-away, and stays untouched.

```kotlin
// HomeScreen.kt ~954-962
val rowAlphaState = animateFloatAsState(
    targetValue = when {
        isExitingNorthWest -> 0f        // unchanged: this is the "fly out" cue
        isActiveRow -> 1f
        else -> 0.7f                    // was 0.45f
    },
    animationSpec = RowChoreographyFloat,
    label = "rowAlpha_${activeTab}_$index"
)
```

### f. Subtitle/captions text overlaps "More Info" when preview plays
`Billboard.kt:565-583` anchors subtitles at `BottomEnd, end = 40, bottom = 40`, but the buttons at `:521-560` live at `BottomStart, bottom = 22`. On widescreen hero artwork the two regions collide — the user sees a button label sliced through by subtitle text. Two complementary fixes:

- Push subtitles to a center-bottom anchor with a max width that respects both side panels (cap at ~52% of billboard width).
- Hide subtitles entirely while `isPlaying` (preview) is true, since the action row is also visible then — the subtitles are there for the *idle* hero, not the preview.

```kotlin
// Billboard.kt ~565-583
Text(
    text = subtitle,
    color = Color.White.copy(alpha = 0.85f),
    fontSize = 14.sp,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier
        .align(Alignment.BottomCenter)               // was BottomEnd
        .widthIn(max = billboardWidth * 0.52f)
        .padding(bottom = 18.dp)
        .alpha(if (isPreviewPlaying) 0f else 1f)     // hide during preview
)
```

```
preview:  subtitles bleed under buttons     subtitles center, hidden during preview
  +-----------------------------+          +-----------------------------+
  | [Play]  [More Info]         |          | [Play]       [More Info]    |
  |                  "..."      |          |                             |
  |              subtitle->     |          |     <- subtitle centered -> |
  +-----------------------------+          +-----------------------------+
```

---

## 6. Cross-cutting / Performance & Detail Refinements

### a. Coalesce the 700ms tween into a single spec family
`HomeScreen.kt:64-70` has 7 `tween(700, FastOutSlowInEasing)` instances (`ChoreographyFloat`, `ChoreographyDp`, `HeaderChoreographyFloat`, `HeaderChoreographyDp`, `RowChoreographyFloat`, `RowChoreographyDp`, `BgColorAnimSpec`) — already consolidated, good. But `TopNavBar.kt:311-322` uses 180ms, `CategoriesBar.kt:189-193` uses 700ms for the ring, `Billboard.kt:419-422` uses 280ms, and `HomeScreen.kt:413` uses 300ms. The values are correct, they're just scattered. A central `Animations.kt` prevents drift and makes timing a single grep target.

```kotlin
// app/src/main/java/com/example/ui/theme/Animations.kt   (new)
package com.example.ui.theme

import androidx.compose.animation.core.*

object AnimationSpecs {
    /** Master horizontal/vertical glide — the 700ms heartbeat. */
    val GlobalTransition = tween<Float>(700, easing = FastOutSlowInEasing)

    /** Focused-pill slide on TopNavBar tab switch. */
    val PillSlide = tween<Float>(150, easing = FastOutSlowInEasing)

    /** Billboard subtitle/description block reveal. */
    val SubtitleExpand = tween<Float>(280, easing = FastOutSlowInEasing)

    /** Categories chip focus ring fade-in/out. */
    val RingFade = tween<Float>(200, easing = FastOutSlowInEasing)

    /** Skeleton → content cross-fade when category cache hydrates. */
    val SkeletonCrossfade = tween<Float>(300, easing = FastOutSlowInEasing)
}
```

### b. The 180ms `requestNavBarFocus()` post-compose call is fine — but `LaunchedEffect(Unit)` for stream warm is misleading
`HomeScreen.kt:194-197` is `LaunchedEffect(Unit) { delay(3000); viewModel.ensureStreamWarmed() }` — runs exactly once, no recomposition storm. (The 180ms `requestNavBarFocus` call the original audit attributed to this block is actually at `HomeScreen.kt:413-416`, a *separate* `LaunchedEffect(Unit)`.) Both `LaunchedEffect(Unit)` blocks are correct; they each fire once per composition root. The only real risk here is lambda capture: every focus change rebuilds the lambdas passed to `TopNavBar` and `NetflixMovieRow`, even though their *behavior* is identical — wrap them in `rememberUpdatedState` (already done for `onPlayMovie/onMovieClick/onCategoryClick` at `:419-421`; extend the same pattern to the per-row callbacks like `onDpadDown`).

```kotlin
// HomeScreen.kt ~1008
val currentOnDpadDown by rememberUpdatedState({ index: Int ->
    if (index < activeDisplayedCategoryRows.size - 1) { /* ... */ }
    else requestNavBarFocus()
})
```

### c. The 116dp categories bar is too tall relative to the 435dp billboard
`HomeScreen.kt:665` `billboardHeight = 435.dp` and `:668` `categoriesHeight = 116.dp` give a 116/435 = **26.7%** ratio. Netflix's hero-bar / categories-bar ratio is closer to **18%** (categories are deliberately compact so the hero owns vertical space). Two coordinated tweaks restore the rhythm without breaking the 700ms vertical glide math at `:680-710`:

- `categoriesHeight`: **116 → 96 dp** (~ -17%)
- `billboardHeight`: **435 → 455 dp** (~ +5%)

```kotlin
// HomeScreen.kt ~664-669
val rowHeight = 395.dp
val billboardHeight = 455.dp     // was 435.dp
val categoriesHeight = 96.dp     // was 116.dp
val billboardSpacing = 10.dp
// kids heights unchanged
```

```
preview:  ratio math
  before:  billboard 435 dp / categories 116 dp = 0.267   (27%)
  after:   billboard 455 dp / categories  96 dp = 0.211   (21%)
  target:  Netflix reference                           ~  18%
```

This also lets `:680-710` recompute `targetScrollYPx` without any other change — the px values are derived from the same `LocalDensity` and the math at lines 699-700 is purely additive.

### d. The "KIDS" amber color is hardcoded four times — promote to a `KidsAccent` constant
`HomeScreen.kt:1132` uses `Color(0xFFFF9D2B)` as a 3dp border on the avatar, `:1168` as the KIDS-badge fill, `:1103-1104` as the radial glow stops. The same hex also lives in `TopNavBar.kt:981-995` (the kids pill border per Section 1a) and `HomeScreen.kt:1141` style references. Five call-sites, one color. Promote to `Color.kt`:

```kotlin
// app/src/main/java/com/example/ui/theme/Color.kt   (add)
val KidsAccent = Color(0xFFFF9D2B)

// Then everywhere:
import com.example.ui.theme.KidsAccent
// ...
.border(3.dp, KidsAccent, RoundedCornerShape(14.dp))
.background(KidsAccent)
```

(`Color.kt` currently exposes `NetflixRed/Black/DarkGrey/LightGrey/White` and the `Primary/Background/...` aliases — `KidsAccent` slots in below the `NetflixWhite` line at `:9`.)

### e. The billboard's bottom-left description has no right-side fade for long copy
`Billboard.kt:447` uses `padding(end = 24.dp)` which spaces the column from the right edge, but a 3-line description that wraps into the right gutter still reads as a hard wall. Add a 60dp horizontal gradient mask (transparent → 0.4 alpha black, left-to-right) over the description's right edge — a Netflix-website style fade that hints at "there's more."

```kotlin
// Billboard.kt ~447
Box {
    Text(description, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 24.dp))
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(60.dp)
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.4f)
                )
            )
    )
}
```

### f. Skeleton → content transition is a hard cut
`HomeScreen.kt:943` renders `HomeScreenSkeleton()` when `activeDisplayedCategoryRows` is empty. When the cache hydrates (`:635-656`), the if/else flips and the skeleton is replaced with the rows in a single frame — no cross-fade. Wrap the body in a `Crossfade` keyed on the boolean, with the `SkeletonCrossfade` spec from 6a.

```kotlin
// HomeScreen.kt ~941-944
val showSkeleton = activeDisplayedCategoryRows.isEmpty()
Crossfade(
    targetState = showSkeleton,
    animationSpec = AnimationSpecs.SkeletonCrossfade,
    label = "rowsSkeletonCrossfade"
) { isLoading ->
    if (isLoading) HomeScreenSkeleton()
    else activeDisplayedCategoryRows.forEachIndexed { ... }
}
```

### g. `topNavOffsetY = 0.dp` (and `contentEntranceOffsetY = 0.dp`) are dead constants
`HomeScreen.kt:155-156` declares `val topNavOffsetY = 0.dp` and `val contentEntranceOffsetY = 0.dp` with a comment claiming "Instant zero-lag entrance matching TV hardware specs" (`:154`). Both are piped into `Modifier.graphicsLayer { translationY = ... }` at `:525` and `:605`, but since they never move they add an extra `graphicsLayer` per Box for nothing. Either delete both, or — if a cold-start slide-in is wanted — drive them from an `Animatable` for a real 350ms entrance:

```kotlin
// HomeScreen.kt ~155-156  (option A: delete)
val topNavOffsetY = 0.dp
val contentEntranceOffsetY = 0.dp
// remove the .graphicsLayer { translationY = topNavOffsetY.toPx() } at :525 and :605

// HomeScreen.kt ~155-156  (option B: real cold-start entrance)
val topNavOffsetY = remember { Animatable(-16.dp) }
val contentEntranceOffsetY = remember { Animatable(16.dp) }
LaunchedEffect(Unit) {
    launch { topNavOffsetY.animateTo(0.dp, tween(350, easing = FastOutSlowInEasing)) }
    launch { contentEntranceOffsetY.animateTo(0.dp, tween(350, easing = FastOutSlowInEasing)) }
}
```

Option B is preferred — a 16dp slide-in over 350ms reads as a graceful launch, and the code becomes load-bearing instead of decorative. Either way, drop the comment "Instant zero-lag entrance" which is no longer accurate.

### h. The categories-bar focus ring (190dp white) stays full-size when scrolled
`CategoriesBar.kt:296-305` renders a 190dp white ring around the focused chip. When `currentFocusLevel >= 1` (rows taking focus), the categories bar fades and shifts up via `categoriesOffsetYState` (`:393-401`), but the white ring stays anchored to the chip — it can draw over the billboard or peek into the first row. Add `clipToBounds()` to the categories bar's Box and let the ring fade with the same `categoriesAlphaState` (already 0f when `isCategoriesAbove`).

```kotlin
// CategoriesBar.kt ~296-305
Box(
    modifier = Modifier
        .clipToBounds()                          // ring won't bleed into billboard
        .graphicsLayer { alpha = categoriesAlpha }
) {
    FocusedChipRing(
        width = 190.dp,
        color = Color.White,
        modifier = Modifier.alpha(
            if (currentFocusLevel >= 1) 0f else 1f    // hide ring when scrolled past
        )
    )
}
```

This piggy-backs on the existing `categoriesAlphaState` (HomeScreen.kt:384-392) which already drops to 0f when `isCategoriesAbove` is true, so no new animation state is needed.

---

## 7. Microcopy / Type

### a. The rating-pill default is inconsistent between Billboard and row
`NetflixMovieRow.kt:581-592` defaults missing ratings to `"13"`. `Billboard.kt:497-508` defaults them to `"18"`. The two pills can show different fallback values for the same `Movie`, which is a trust bug — users will read the row pill (current focus) as authoritative. Pick one default and apply it everywhere.

**Recommendation: `"13+"` for both.**

Why `"13+"`:
- It is already the row's effective default (`ifBlank { "13" }` at `:587`), so the change is a one-file align.
- It is the most internationally portable form (US `TV-14`, EU `12`, AU `M` all collapse to roughly the same "mid-teen" middle).
- It mirrors Netflix's own catalogue, where mid-teen content is the modal rating and the missing-data default of `"18"` reads as alarmist.

```kotlin
// Theme.kt (new) — single source of truth
object RatingDefaults {
    const val MISSING = "13+"       // Billboard + Row + Kids row all use this
}

// NetflixMovieRow.kt:587
Text(text = movie.rating.ifBlank { RatingDefaults.MISSING }, ...)

// Billboard.kt:497-508 — replace any hardcoded "18" default
Text(text = movie.rating.ifBlank { RatingDefaults.MISSING }, ...)
```

```
preview:
before:                          after:
+---+     +---+                  +----+    +----+
|18 |     |13 |                  |13+|    |13+|
+---+     +---+                  +----+    +----+
Billboard  Row                   Billboard  Row
  ^                              ^
  inconsistent                   "13+" everywhere,
  "18" is alarmist               trustworthy default
```

### b. "Series" type fallback is wrong in Films tabs
`NetflixMovieRow.kt:559-563` falls back to `"Series"` when `movie.type` is blank, even when the row lives inside a Films tab. The badge then misrepresents the catalogue.

**Recommendation: default to `"Movie"` for missing type.**

Why `"Movie"` over hiding:
- A catalogue is ~80% films; the modal default is the truthful one.
- Hiding creates a visible gap before the first bullet (`:565`) and breaks the metadata rhythm — three tight items followed by two tight items reads as a typo.
- `"Series"` was actively misleading in Films tabs; the existing fallback is the worse of the two errors.

If row context ever becomes available, prefer `movie.type.ifBlank { if (isFilmsTab) "Movie" else "Series" }` — but for the audit pass, one global default is the right call.

```kotlin
// NetflixMovieRow.kt:559-563
Text(
    text = movie.type.ifBlank { "Movie" },     // was "Series"
    color = Color.White,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold
)
```

```
preview:
before (Films tab):              after (Films tab):
Series • 2025 • 1h 42m [13]      Movie • 2025 • 1h 42m [13+]
  ^^^                              ^^^^
  wrong in Films tab              "Movie" default
  contradicts the tab label       matches the tab label
```

### c. The dot separators look thinner than the surrounding text
`NetflixMovieRow.kt:565, 572, 579` render `Text("•", color = Color.Gray, fontSize = 13.sp)` with the default `FontWeight.Normal`. The flanking fields use `FontWeight.Bold` and `SemiBold` (`:562, 569, 575, 588`), so the bullet reads as the lightest element in the row and the rhythm stutters. Swap the unicode bullet for a small `Box` dot at the same vertical centre — the weight is consistent and the size is independent of font metrics.

```kotlin
// NetflixMovieRow.kt:565 (and :572, :579) — replace Text("•") with Box dot
@Composable
private fun MetadataDot() {
    Box(
        modifier = Modifier
            .size(4.dp)
            .background(Color.Gray.copy(alpha = 0.7f), CircleShape)
    )
}

// inline usage between fields:
Text("Series", ...) ; MetadataDot() ; Text("2025", ...) ; MetadataDot() ; ...
```

```
preview:
before:                          after:
Series • 2025 • 1h 42m [13+]     Series · 2025 · 1h 42m [13+]
       ^        ^                       ·        ·
       unicode bullet,                4dp circle, alpha 0.7f,
       normal weight                  matches the surrounding
       looks thinner                  Bold / SemiBold weight
```

---

## Quick Win Priority

If you only do 5 of these, do these:

1. **`HomeScreen.kt:155-156` — kill or wire the dead `topNavOffsetY` / `contentEntranceOffsetY` constants.** *Why first:* dead code that costs a `graphicsLayer` pass on every recomposition of the TopNavBar and the content Box; either delete or convert to a real 350ms cold-start entrance — both are 5-line changes.
2. **`HomeScreen.kt:958` — lift inactive row alpha from `0.45f` to `0.7f`.** *Why first:* the largest visible win in the entire audit. A one-line value change fixes the "rows look broken" reading at couch distance and is risk-free (the 0f exit-cue animation is untouched).
3. **`CategoriesBar.kt:325` — actually use the `moodColor` parameter that's already being passed in.** *Why first:* it's dead-on-arrival code today. Wiring `getCategoryThemeColor(title)` into the focused-chip gradient turns the categories bar into a palette preview at zero animation cost.
4. **`HomeScreen.kt:1065-1199` — shorten the kid-profile overlay to 900ms with 0.88f alpha + progress bar.** *Why first:* the overlay is the single most "is the app frozen?" moment on the screen. Three small edits (`:1070` delay, `:1094` alpha, replace `NetflixSpinner` with `LinearProgressIndicator` + label) turn a hang into a transition.
5. **`Billboard.kt:527-531` — shorten locked-button text from "Upgrade Plan to Unlock" to "Upgrade".** *Why first:* the cheapest fix in the audit (one string change) that prevents the only known layout-overflow bug on 720p TVs.

---

## File Reference

| File | Sections |
|------|----------|
| `app/src/main/java/com/example/ui/components/TopNavBar.kt` | 1, 6 |
| `app/src/main/java/com/example/ui/components/Billboard.kt` | 2, 6, 7 |
| `app/src/main/java/com/example/ui/components/CategoriesBar.kt` | 3, 6 |
| `app/src/main/java/com/example/ui/components/NetflixMovieRow.kt` | 4, 7 |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | 5, 6 |
| `app/src/main/java/com/example/ui/theme/Color.kt` (suggested) | 6d |
