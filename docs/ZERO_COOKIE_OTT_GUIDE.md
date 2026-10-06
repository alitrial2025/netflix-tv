# OTT Playback Architecture — Complete Zero-Cookie Guide

> **Status**: All 3 active OTTs on `net52.cc` — **Netflix**, **Prime Video**, and **Disney+ Hotstar** — have fully proven zero-cookie playback pipelines. NF and PV require a single outside-API scrape (`netflix.com` / `primevideo.com`) for season discovery. HS/Disney+ is **100% cookie-free end-to-end**.

---

## 1. Cookie Boundary — The Facts

| Endpoint | Netflix (`nf`) | Prime Video (`pv`) | Disney+ Hotstar (`hs`) |
|---|:---:|:---:|:---:|
| **search** | ✅ `/search.php` (no cookie) | ✅ `/mobile/pv/search.php` (no cookie) | ❌ needs session |
| **post.php** (show details + seasons) | ❌ **"Invalid User"** | ❌ **"Invalid User"** | ✅ **cookie-free!** |
| **episodes.php** | ✅ no cookie | ✅ no cookie | ✅ no cookie |
| **playlist.php** | ✅ no cookie | ✅ no cookie | ✅ no cookie |
| **HLS manifest** | ✅ no cookie | ✅ no cookie | ✅ no cookie |
| **CDN segments** | ✅ no cookie | ✅ no cookie | ✅ no cookie |

> [!IMPORTANT]
> **Why NF and PV gate `post.php`**: The platform is called **Net**Mirror — Netflix is the core product, monetized via ads. The `post.php` cookie requirement forces users through the ad/handshake flow. PV follows the same gating. HS (`/mobile/hs/post.php`) was added later and is **completely open** — returns full show metadata, seasons, season IDs, and episodes with zero cookies.

### Outside API Required (NF and PV only)

Because NF and PV `post.php` returns "Invalid User" without cookies, we scrape the **original platform's public pages** for season IDs:

| OTT | Outside API | What It Gives Us | Cookie Needed? |
|---|---|---|:---:|
| **Netflix** | `netflix.com/title/{showId}` | Season IDs embedded in HTML | ❌ No |
| **Prime Video** | `primevideo.com/detail/{showId}` | Season IDs embedded in HTML | ❌ No |
| **Disney+ Hotstar** | **Not needed** — `post.php` works! | Seasons, episode IDs, metadata | ❌ No |

---

## 2. Complete Zero-Cookie Pipelines

### Netflix (needs netflix.com for seasons)

```mermaid
flowchart LR
    A["/search.php\n→ Show ID"] --> B["netflix.com/title/{id}\n→ Season IDs"]
    B --> C["/mobile/episodes.php\n→ Episode IDs"]
    C --> D["Self-build token\n→ ?in= param"]
    D --> E["/mobile/hls/{id}.m3u8\n→ CDN URL"]
    E --> F["nm-cdn → Video"]

    style A fill:#22c55e,color:#000
    style B fill:#f59e0b,color:#000
    style C fill:#22c55e,color:#000
    style D fill:#22c55e,color:#000
    style E fill:#22c55e,color:#000
    style F fill:#22c55e,color:#000
```

> 🟢 = zero-cookie on net52 &nbsp; 🟡 = outside API (still no cookies)

### Prime Video (needs primevideo.com for seasons)

```mermaid
flowchart LR
    A["/mobile/pv/search.php\n→ Show ID"] --> B["primevideo.com/detail/{id}\n→ Season IDs"]
    B --> C["/mobile/pv/episodes.php\n→ Episode IDs"]
    C --> D["Self-build token\n→ ?in= param"]
    D --> E["/mobile/pv/hls/{id}.m3u8\n→ CDN URL"]
    E --> F["nm-cdn → Video"]

    style A fill:#22c55e,color:#000
    style B fill:#f59e0b,color:#000
    style C fill:#22c55e,color:#000
    style D fill:#22c55e,color:#000
    style E fill:#22c55e,color:#000
    style F fill:#22c55e,color:#000
```

### Disney+ Hotstar (100% cookie-free!)

```mermaid
flowchart LR
    A["Cached Catalog\n→ Show ID"] --> B["/mobile/hs/post.php\n→ Seasons + Episodes"]
    B --> C["Self-build token\n→ ?in= param"]
    C --> D["/mobile/hs/hls/{id}.m3u8\n→ CDN URL"]
    D --> E["freecdn → Video"]

    style A fill:#22c55e,color:#000
    style B fill:#22c55e,color:#000
    style C fill:#22c55e,color:#000
    style D fill:#22c55e,color:#000
    style E fill:#22c55e,color:#000
```

> [!NOTE]
> HS `post.php` returns **everything** in one call: title, year, creator, cast, genre, seasons with IDs, episodes with IDs/titles/durations, available languages, and related suggestions — all without a single cookie.

---

## 3. True OTT Mapping & Routing

In NetMirror, platforms are consolidated into 3 active backend routes:

| Route | Primary Branding | Content Also Includes | CDN Provider |
|:---:|---|---|---|
| **`nf`** | **Netflix** | Netflix Originals, licensed content | `nm-cdn` (`s23.nm-cdn9.top`) |
| **`pv`** | **Prime Video** | Apple TV+, MX Player, Lionsgate, CrunchyRoll, Anime Times, ErosNow | `nm-cdn` (`s11.nm-cdn8.top`) |
| **`hs`** | **Disney+ Hotstar** | **Disney+**, **Marvel**, **Star Wars**, **Pixar**, **HBO Max**, **Paramount**, **Peacock**, DC | `freecdn` (`s30.freecdn31.top`) |

> [!TIP]
> The `nf-custom.js` client script labels the HS search section as `<h2>Disney+ HotStar</h2>`. All Disney, Marvel, and HBO content (Ironheart, Loki, Lanterns, Mulan, etc.) lives under the `/mobile/hs/` endpoint tree.

---

## 4. Endpoint Pattern Per OTT

| Endpoint | Netflix (`nf`) | Prime Video (`pv`) | Disney+ Hotstar (`hs`) |
|----------|----------------|--------------------|-----------------------|
| **search** | `/search.php` | `/mobile/pv/search.php` | `/mobile/hs/search.php` ⚠️ |
| **post** (seasons) | `/mobile/post.php` ⚠️ | `/mobile/pv/post.php` ⚠️ | `/mobile/hs/post.php` ✅ |
| **episodes** | `/mobile/episodes.php` | `/mobile/pv/episodes.php` | `/mobile/hs/episodes.php` |
| **playlist** | `/mobile/playlist.php` | `/mobile/pv/playlist.php` | `/mobile/hs/playlist.php` |
| **HLS** | `/mobile/hls/{id}.m3u8` | `/mobile/pv/hls/{id}.m3u8` | `/mobile/hs/hls/{id}.m3u8` |
| **posters** | `imgcdn.kim/nf/{size}/{id}.jpg` | `imgcdn.kim/pv/{size}/{id}.jpg` | `imgcdn.kim/poster/{size}/{id}.jpg` |
| **subtitles** | `nf.subscdn.top` | `pv.subscdn.top` | inline |

> ✅ = zero-cookie &nbsp; ⚠️ = needs cookies

---

## 5. ID Formats

| OTT | Show/Season ID | Episode ID | Example |
|-----|---------|-----------|---------|
| **NF** | 8-digit numeric (`[5-9]\d{7}`) | 8-digit numeric | `70155584` (Smallville) |
| **PV** | 26-char alphanumeric (`0[A-Z0-9]{25}`) | 26-char alphanumeric | `0NSPK8CXRVWRF9LXT1WGZ9MES0` (The Boys) |
| **HS** | 10-digit numeric (`1[0-9]{9}`) | 10-digit numeric | `1271341039` (Ironheart) |

---

## 6. Universal Token Formula

Identical across all 3 OTTs:

```
HASH1     = "235ca31540ab8d90fcef4a00de8a247c"
timestamp = floor(Date.now() / 1000)
hash2     = MD5(timestamp + contentId)

masterToken = HASH1 + "::" + hash2 + "::" + timestamp + "::ek::m"
cdnToken    = HASH1 + "::" + hash2 + "::" + timestamp + "::ek::myes"
```

> [!WARNING]
> Always use **`::ek::m`** mode. The `::eb::m` mode triggers rate-limit fallback videos on HS CDN. The `::su::*` modes are rejected everywhere.

---

## 7. Confirmed Titles (Warm Diagnostic Proof)

### Disney+ Hotstar — Full CDN Playback Verified

| Title | Type | Show ID | Seasons | Episode 1 ID | Audio Langs | CDN |
|---|:---:|:---:|:---:|:---:|---|---|
| **Ironheart** | TV (2025) | `1271341039` | S1: `1271341037` (6 eps) | `1271341040` | EN, TE, TA, TH, HI | `s30.freecdn31.top` |
| **Lanterns** | TV (2026) | `1271680756` | S1: `1271684185` (7 eps) | `1271684191` | EN, TA, HI | `s30.freecdn31.top` |
| **Loki** | TV (2023) | `1260063451` | S1: `1260063524`, S2: `1260148326` | `1260063525` | EN +10 | `s30.freecdn31.top` |
| **Mulan** | Movie (2020) | `1260048586` | Film | `1260048586` | EN, HI, MS, ID, TH, TE, TA | `s30.freecdn31.top` |

### Netflix — Zero-Cookie Proven

| Title | Seasons Found | Pipeline Time |
|---|:---:|:---:|
| **Smallville** | 10 seasons via netflix.com | 15.1s |
| **Wednesday** | 2 seasons | — |
| **Squid Game** | 3 seasons | — |

### Prime Video — Zero-Cookie Proven

| Title | Seasons Found | Method |
|---|:---:|---|
| **Reacher** | 3 seasons via primevideo.com | zero-cookie |
| **The Boys** | S1 | zero-cookie |
| **Jack Ryan** | S1 | zero-cookie |
| **Lost** | 200 episodes | zero-cookie |

---

## 8. Warm Diagnostic Result — Ironheart S1E1

```
✅ Domain:      net52.cc → HTTP 200 (827ms)
✅ Handshake:   t_hash_t received (34.7s)
✅ Search:      HS found "Ironheart" ID=1271341039
✅ Seasons:     1 season (Season 1: ID=1271341037, 6 episodes)
✅ Episode:     S1E1 "Take Me Home" → Content ID: 1271341040
✅ Playlist:    2 HLS sources, 4 subtitle tracks
✅ Token:       ::ek::m accepted (978B manifest)
✅ Manifest:    247 video segments, EXT-X-ENDLIST
✅ Segments:    mpeg_ts | 175,592B | HTTP 200 OK
✅ No-Cookie:   CDN manifest works WITHOUT cookies
```

---

## 9. Reference Scripts

| Script | Purpose |
|---|---|
| [`diagnose-playback.mjs`](file:///d:/Netflixtv/tools/cdn-audit/diagnose-playback.mjs) | Full 9-stage warm pipeline diagnostic |
| [`zero-cookie-pipeline.mjs`](file:///d:/Netflixtv/tools/cdn-audit/zero-cookie-pipeline.mjs) | NF zero-cookie A→Z proof |
| [`pv-zero-cookie-pipeline.mjs`](file:///d:/Netflixtv/tools/cdn-audit/pv-zero-cookie-pipeline.mjs) | PV zero-cookie A→Z proof |
| [`hs-final-resolve.mjs`](file:///d:/Netflixtv/tools/cdn-audit/hs-final-resolve.mjs) | HS post.php zero-cookie breakthrough |
| [`ott-catalog.json`](file:///d:/Netflixtv/tools/cdn-audit/ott-catalog.json) | Pre-cached HS + PV catalog (show/episode IDs) |
