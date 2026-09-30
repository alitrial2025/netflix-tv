# Kids character cutouts

This folder supplies the transparent cartoon characters above the five Kids
title cards. These are separate images from the title's backdrop, poster and logo.

Use a genuine title-specific character PNG or WebP with an alpha channel.
Name it `movie_<TMDB ID>.webp` or `tv_<TMDB ID>.webp`; `.png` is also supported.
The media kind is required because TMDB movie and television IDs can overlap.

IMDb names also work: `tv_tt7678620.png`, `movie_tt14362112.webp`, or
`tt7678620.png`. When a title has only a TMDB ID, the Kids hero uses TMDB's
movie/TV `external_ids` endpoint to find its IMDb ID. This happens only when
IMDb-only artwork exists, after Home is ready and input is idle. Requests are
serialized, limited to four seconds, and cached; missing or offline results do
not block the UI. The movie and TV namespaces stay separate.

`catalog.json` can connect an artwork file with both identities. Each entry in
`artworks` has `media_kind`, optional `tmdb_id`/`imdb_id`, and `file`. For an
explicit remote image, use `url` with a direct HTTPS image address. `image_url`
and `source_page` record provenance only; the app does not visit those pages.
Local `movie_<ID>`/`tv_<ID>` files take priority over registered remote images.

The character should face into the title card, have transparent space around it,
and sit on the bottom edge of a roughly 320 x 240 pixel canvas. Keep the original
character's shape; do not add a circular background. A compact transparent WebP
keeps the installed size and decode memory small.

Obtain the matching character art from the title's official press or marketing
assets, or prepare a transparent cutout from suitable title-specific artwork.
TMDB posters and character profile avatars are not substitutes for this artwork.

Official starting points:

- Bluey character artwork: https://www.bluey.tv/characters/bluey/
  (the page links to https://www.bluey.tv/wp-content/uploads/2023/07/Bluey.png).
- Bluey Media Hub: https://www.bluey.tv/media-hub/ — official logos, key art
  and brand assets.
- DreamWorks Trolls: https://www.dreamworks.com/trolls — character and
  franchise artwork.
- Netflix Media Center: https://media.netflix.com/en/ — title publicity assets.

Use artwork licensed for distribution in the app. A publicly accessible image
does not automatically include permission to redistribute it. The exact Netflix
TV character cutouts are not confirmed to be available as a public asset pack.

Four unmodified transparent reference assets are now bundled from official
character pages, with identities verified against TMDB:

| Title | TMDB filename | IMDb ID | Character |
| --- | --- | --- | --- |
| Bluey (2018) | `tv_82728.png` | `tt7678620` | Bluey |
| Bluey Minisodes | `tv_256953.png` | `tt32328599` | Bingo |
| Trolls World Tour | `movie_446893.png` | `tt6587640` | Poppy |
| Trolls Band Together | `movie_901362.png` | `tt14362112` | Poppy |

The app decodes each image at the small character slot size with its alpha
channel preserved. The four bundled files need no artwork network requests.
The selected titles still come from the app's existing featured catalogue;
adding these files does not force those titles into the five Kids cards.

Netflix Media Center is recorded as a source awaiting title-specific cutouts.
No public pack of its exact Kids TV cutouts was found. An IMDb ID identifies
the title; it does not turn a poster or press still into a transparent character.
Other titles omit the cutout until matching artwork is added.

The reference downloads are not a redistribution licence. Distribution rights
for these files have not been confirmed; replace them with your licensed files
or confirm the rights before distributing the app.
