# TV navigation, previews and subtitle selection — 2026-10-18

- Search activates without the tab-hover debounce. Its bounded artwork grid starts after two frames without waiting for the global browsing-idle delay.
- The active, visible billboard can request a preview without requiring focus on one of its buttons. Navigation away and lifecycle changes cancel that request. Billboard, cards, Details and full playback continue using the ViewModel's single ExoPlayer and ownership handoff.
- Billboard startup has a bounded 45-second budget for shared request spacing and cold resolution. Cards retain their 15-second budget; provider cooldowns, cancellation and request spacing remain enforced.
- The Details metadata line (type, genre, year, duration and badges) wraps whole items within the available width instead of clipping the end.
- Subtitle parsers retain unknown languages as `und`, normalize ISO codes, and mark conflicting label/code/filename evidence as unconfirmed. Selecting English cannot fall back to an unrelated default or forced subtitle. Mobile retains the exact supported track selected in its menu.

Regression coverage includes narrow metadata at 1.4 font scale, shared playback ownership, preview budgets, language aliases, missing/conflicting metadata and actual Media3 track groups. Both release workflows run the complete test suite before signed APK publication.

No specific mislabeled title was available to inspect subtitle file contents. These changes correct metadata handling and selection; they cannot establish the spoken language inside a file whose provider metadata is consistently incorrect. Physical-TV navigation timing and decoded preview playback still need device verification.
