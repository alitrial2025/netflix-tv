# NetflixPro advert website

The existing TV screen tour is preserved. A second, interactive phone tour follows the current Kotlin Home, Details, Downloads, Smart Downloads, Search and Edit Profile layouts. The phone showcase uses HTML/CSS and bundled artwork/title logos, rather than screenshots. Demo controls are local preview state and never touch an account.

The phone keeps the existing floating navigation on Home and Search. Details, Downloads, Smart Downloads and Edit Profile omit it. Details uses the TMDB title logo and a poster-coloured gradient; switches use the same blue/white styling as the app. Reduced-motion preferences disable animations.

Artwork and transparent title logos were copied from the mobile application's existing native UI fixtures (`app/src/test/resources/artwork`) and compressed to WebP. These are illustrative catalogue fixtures, not current trending results. No API credentials are embedded in the website.

## Preview and checks

This is a static website. No bundler or build command is required.

```sh
python -m http.server 8765 --directory marketing-site
```

Open `http://localhost:8765/#phone-tour`. For the browser checks, install Python Playwright and Chromium, then run from the repository root:

```sh
python marketing-site/tests/phone-tour-browser.py
node --check marketing-site/assets/mobile-tour.mjs
node --test tools/update-gate/release-lib.test.mjs
```

The browser check starts its own local server, checks all six phone screens, in-preview navigation, independent switches, storage allocation, profile/My List preview state, the existing TV tour, responsive widths 390/768/1920, reduced motion and uncaught JavaScript errors. Review images default to `/tmp/netflixpro-phone-tour-review`; override with `PHONE_TOUR_REVIEW_DIR`.

## Downloads and publishing

`updates/mobile.json` now points to the verified **1.3 debug review APK** in the existing GitHub download branch, including its exact size, SHA-256 and debug certificate fingerprint. This is explicitly labelled a debug review build, not a production-signed release. Keep this metadata aligned whenever that artifact changes. The TV download metadata is unchanged.

For Netlify Git deploys, base directory `marketing-site`, publish directory `.`, build command blank. The included `netlify.toml` and `_headers` remain in use. This change prepares the website source and local preview; no production deployment was performed.
