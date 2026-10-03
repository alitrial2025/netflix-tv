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

`updates/mobile.json` and `updates/tv.json` identify the existing **debug review APKs**, including their exact size, SHA-256, certificate fingerprint and `buildType: "debug"`. Keep the metadata aligned whenever the actual artifacts change. The website labels debug builds beside each download button and provides expandable file details. The release publishing tool emits `buildType: "release"` only for its release-signing flow.

The Vercel project is **netflixpro** under **alitrial2025-9479**, linked from `marketing-site`. Deploy this folder, not the Android repository root:

```sh
vercel link --yes --project netflixpro --scope alitrial2025-9479
vercel deploy --prod --scope alitrial2025-9479
```

The current public address is **https://npro-app.vercel.app/**, attached to the existing `netflixpro` project and declared in the page's canonical link. `netflixpro.vercel.app` remains attached for existing app update URLs. The earlier CLI link retained the stale project name `marketing-site`; re-linking resolves the resulting authorization/project lookup errors. `npro.vercel.app` was unavailable, so the owner chose `npro-app.vercel.app`.

The site has independent project branding and a prominent Netflix non-affiliation notice, visible developer identity (mzazimhenga), website privacy information, restricted APK sources and same-origin-only scripts. GitHub profile, source-code and support links have been removed from the page; APK downloads still use the existing project host. The Vercel upload ignores local environment files, tests and the unlinked obsolete bundled APK. Never publish `.env.local`, keystores or signing properties.

For Netlify Git deploys, base directory `marketing-site`, publish directory `.`, build command blank. The included `netlify.toml` and `_headers` remain in use. Google Safe Browsing warnings require Google's review; a deployment alone does not clear them. See `SECURITY-REVIEW.md`.
