# Clipper

A browser bookmarklet and Spring Boot app for saving web page clips. Click the bookmarklet on any page and Clipper captures the title, URL, Open Graph metadata, tags, selected text, page body text, and candidate images directly from the live DOM — no server-side scraping required. A two-step popup composer lets you review and curate the clip before submitting. When you save, Clipper downloads and caches the selected images locally so saved cards render from your own copy — independent of the original site.

---

## How it works

1. You click the **Clip it** bookmarklet on any page.
2. The bookmarklet injects `clipper.js` from the Clipper server into the page.
3. `clipper.js` collects metadata, tags, images, and body text from the live DOM and POSTs them to `POST /clip`.
4. The server sanitises the payload, stores it in memory with a 1-hour TTL, and returns a `clipId` and a `composeUrl`.
5. `clipper.js` opens a popup pointing at `GET /clip/{clipId}`.
6. The composer popup opens in two steps:
   - **Step 1 — Edit:** Review and edit the captured text, manage tags, and select one or more images from the candidate grid.
   - **Step 2 — Preview:** See how the card will look, with all selected images displayed together, then submit.
7. On submit, the server caches each selected image locally, then saves the post to H2 (including page body text, which is indexed in Lucene for full-text search).
8. The saved post page (`GET /post/{id}`) renders entirely from cached local images — no dependency on the original site.

---

## Features

### Metadata capture

The bookmarklet collects the following fields from every page:

| Field | Source |
|---|---|
| `url` | `window.location.href` |
| `title` | `document.title` |
| `selectedText` | Active text selection at click time |
| `pageText` | Visible body text (nav/header/footer/scripts stripped, capped at 100 KB) |
| `description` | `<meta name="description">` |
| `canonicalUrl` | `<link rel="canonical">` |
| `ogTitle` | `<meta property="og:title">` |
| `ogDescription` | `<meta property="og:description">` |
| `ogImage` | `<meta property="og:image">` |

All text extraction runs client-side in the browser, so it works behind logins and paywalls without any extra server-side request.

### Full-text search

The home page has a search bar that queries all saved posts via a Lucene index. Searching uses an **English analyzer** (Porter stemming + stopword removal, so "running" matches "run") and prefix matching on each word. The index covers:

- Title (OG title if edited, original title otherwise)
- Selected / edited body text
- Meta description
- Tags
- Page body text (captured at clip time)

Search results are returned in relevance order. Clearing the query (or submitting an empty one) returns all posts in reverse-chronological order.

### Tag extraction

Tags are aggregated from multiple sources and deduplicated (lowercased, max 100 chars each, up to 50 total):

- `<meta name="keywords">`, `news_keywords`, `category`, `tags`
- `<meta property="article:section">` and repeating `article:tag`
- `<a rel~="tag">` links — covers WordPress, Baeldung, and most CMS-driven sites
- Breadcrumb navigation (`nav[aria-label*="breadcrumb"]`, `.breadcrumb a`, `#wayfinding-breadcrumbs_feature_div a`, and Schema.org microdata) — covers Amazon and most e-commerce / news sites that skip meta keywords
- Schema.org JSON-LD blocks: `keywords`, `articleSection`, and `about[].name`

### Image collection and filtering

Images are collected from the OG image tag (highest priority) and all `<img src>` elements on the page. Images are filtered to remove obvious icons and tracking pixels:

- At collection time in the bookmarklet: images where **both** known dimensions are below **300 px** are dropped before the payload is sent to the server.
- After rendering in the composer grid: the `onload` handler checks true natural dimensions and hides any image where **both** dimensions are below **300 px**.
- Broken images (CORS errors, 404s) are hidden via `onerror`.
- Up to 20 images reach the server; the grid shows whichever pass the size filter.

### Two-step composer

**Step 1** presents all captured data for review:
- Editable textarea for the selected text
- Meta description (if present)
- Tag chips with add/remove — pre-populated from the page's tag sources
- Image grid for multi-select — click to toggle, selected images show a check badge

**Step 2** shows the preview card:
- All selected images displayed in a responsive grid (no pagination)
- Title, body text, and source link
- Back button to return and adjust; Submit button to save the clip

### Image caching

When the user submits in Step 2, the server caches all selected images before saving the post. This decouples saved posts from the original site — images continue to display even if the source site moves, hotlink-blocks, or deletes them.

**Download safety:**
- Only `http` and `https` URLs are accepted; `file:`, `data:`, `javascript:`, and other schemes are rejected.
- Hostnames are resolved before connecting; private, loopback, link-local, and CGNAT addresses are blocked to prevent SSRF.
- Redirects are followed up to a configurable limit, with the same address checks applied at each hop.
- Downloads are capped at a configurable maximum size (default 10 MB).
- The `Content-Type` header must begin with `image/`; SVG is rejected.
- The downloaded bytes are decoded with `ImageIO` to confirm they are a valid image.

**Storage:**
- Files are written atomically: the server downloads to a temp file, validates, then moves into place.
- Filenames are derived from the SHA-256 of the downloaded bytes (e.g. `a3f8...c2.jpg`); the original filename is not used.
- Duplicate images (same bytes, different URLs) are deduplicated by checksum — only one file is stored.
- A thumbnail is generated alongside each original (default max dimension: 400 px).
- Image metadata (original URL, local path, thumbnail path, dimensions, content type, checksum, cache status) is stored in H2.

**On failure:**
- If any selected image cannot be cached, the save is aborted and the composer displays a per-image error. The user can go back, deselect the failing image, and retry.

**File layout:**

```
~/.clipper/
  clipperdb.mv.db        # H2 database (posts + image metadata)
  search-index/          # Lucene full-text index
  images/
    originals/          # Full-size cached images (<sha256>.<ext>)
    thumbnails/         # Resized copies (<sha256>.<ext>)
```

### Home page

`GET /` shows a card grid of all saved posts, most recent first. Each card shows the primary image (thumbnail preferred), title, excerpt, and tags. Clicking a card opens the post view; the ✎ icon opens the edit page directly, and the 🗑 icon deletes the post after a confirmation prompt. A search bar at the top filters cards via full-text search.

### Saved post view

`GET /post/{id}` renders the saved post using only cached local images. It shows:
- Page title with an ↗ link icon to the original page
- Selected cached images (only those marked as selected)
- Selected / edited body text
- Tags
- Home, Edit, and Delete buttons in the header (Delete asks for confirmation)

### Editing

`GET /post/{id}/edit` opens an edit form. You can:
- Update the title, body text, and tags
- Toggle which cached images are shown (click to select/deselect)
- Fetch fresh image candidates from the original source URL with **Fetch from source** — the server re-fetches and Jsoup-parses the page, returning image candidates you can select to cache and add

Edits save via JSON (`POST /post/{id}/edit`) and re-index the post in Lucene.

### Theme

All pages support dark and light themes with a toggle button in the header. The preference is persisted in `localStorage`.

---

## Stack

| Layer | Technology |
|---|---|
| Server | Spring Boot 3.3 · Java 17 |
| Templates | Thymeleaf |
| Bookmarklet | Vanilla JS (ES5, no dependencies) |
| Clip store | In-memory `ConcurrentHashMap` with 1-hour TTL |
| Post store | H2 (file-based) via Spring JDBC (`~/.clipper/clipperdb.mv.db`) |
| Full-text search | Apache Lucene with `EnglishAnalyzer` (Porter stemmer) |
| Image cache | Local filesystem (`~/.clipper/images/`) |
| Thumbnails | Thumbnailator |
| HTML parsing | Jsoup (source-image fetch on edit page) |
| Tests | JUnit 5 + MockMvc + Mockito |

---

## Database

Clipper uses a file-based H2 database at `~/.clipper/clipperdb.mv.db` (configurable via `clipper.data-dir`). The schema is created automatically on first run via `CREATE TABLE IF NOT EXISTS`.

### Tables

**`posts`** — one row per saved clip.

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR PK | UUID |
| `clip_id` | VARCHAR | ID of the originating in-memory clip |
| `url` | VARCHAR | Source page URL |
| `title` | VARCHAR | Page title (`document.title`) |
| `og_title` | VARCHAR | OG title; cleared to `''` after a manual edit |
| `selected_text` | CLOB | User-selected or edited description |
| `description` | CLOB | `<meta name="description">` |
| `page_text` | CLOB | Visible body text captured by the bookmarklet (up to 100 KB) |
| `tags` | VARCHAR | JSON array of tag strings |
| `created_at` | VARCHAR | ISO-8601 instant |

**`cached_images`** — one row per candidate image per post.

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR PK | UUID |
| `post_id` | VARCHAR FK | References `posts.id` |
| `original_url` | VARCHAR | Source URL |
| `local_path` | VARCHAR | Relative path under `images/originals/` |
| `thumbnail_path` | VARCHAR | Relative path under `images/thumbnails/` |
| `sha256` | VARCHAR | Hex SHA-256 of file bytes (used for deduplication) |
| `cache_status` | VARCHAR | `cached` · `failed` · `rejected` |
| `selected` | INTEGER | `1` = shown on the post, `0` = hidden |
| `rank_order` | INTEGER | Display order |
| … | | width, height, content_type, byte_size, alt_text, kind, cached_at, cache_error |

### Full-text search (Lucene)

A Lucene index lives alongside the database at `~/.clipper/search-index/`, with one document per post covering these fields:

- `title`, `og_title`, `selected_text`, `description`, `tags_text`, `page_text`

Each field is analyzed with Lucene's `EnglishAnalyzer`, which stems words to their root form (Porter stemming) and strips common stopwords — so a search for "running" also matches "run", "runs", and "runner".

The index is kept in sync with the `posts` table:
- Rebuilt from scratch on every startup (`IndexWriter.deleteAll()` + re-add from all DB rows), so it can never drift out of sync with the database.
- A document is upserted (`updateDocument`, keyed by post ID) when a post is saved or edited.
- The document is deleted when a post is deleted.

Each query word is matched as a `BooleanQuery` combining a prefix query (raw term) and a term query (stemmed form) across every field, OR'd together; per-word queries are then AND'd, so all words must match somewhere. Results are returned in Lucene's default BM25 relevance order.

### Connection pool

Unlike SQLite, H2 supports concurrent writers, so HikariCP is configured with `maximum-pool-size=5`.

---

## Running

```bash
mvn spring-boot:run
```

The server starts on `http://localhost:8080`.

---

## Installing the bookmarklet

### Development (localhost)

Create a browser bookmark with this URL:

```
javascript:(()=>{const s=document.createElement('script');s.src='http://localhost:8080/clipper.js';document.body.appendChild(s);})();
```

Name it **Clip it** and add it to your bookmarks bar.

### Production

Replace `http://localhost:8080` with your deployed app's base URL. The bookmarklet derives the app origin from its own `<script src>`, so no other changes are needed.

---

## Configuration

All settings are in `application.properties`. The defaults are shown below.

```properties
# Directory for the H2 database, Lucene index, and image cache
clipper.data-dir=${user.home}/.clipper

# Image download limits
clipper.image.max-bytes=10485760          # 10 MB
clipper.image.connect-timeout-ms=10000   # 10 s
clipper.image.read-timeout-ms=30000      # 30 s
clipper.image.max-redirects=5

# Set true to allow downloading from localhost/private IPs (development only)
clipper.image.allow-private-addresses=false
```

---

## Running the tests

```bash
mvn test
```

Tests use JUnit 5, MockMvc, and Mockito and do not require a running server. Integration tests write to `/tmp/clipper-test`. `ImageCacheServiceTest` starts an embedded JDK HTTP server to exercise the full download and caching path.

---

## API

### `POST /clip`

Accepts a JSON payload and returns a compose URL.

**Request body**

```json
{
  "url": "https://example.com/article",
  "title": "Article title",
  "selectedText": "Text the user highlighted",
  "pageText": "Full visible body text of the page (up to 100 KB)",
  "description": "Meta description",
  "canonicalUrl": "https://example.com/article",
  "ogTitle": "OG title",
  "ogDescription": "OG description",
  "ogImage": "https://example.com/image.jpg",
  "images": [
    { "src": "https://example.com/image.jpg", "alt": "", "kind": "og_image", "width": null, "height": null }
  ],
  "keywords": ["java", "spring"]
}
```

**Response**

```json
{
  "clipId": "550e8400-e29b-41d4-a716-446655440000",
  "composeUrl": "/clip/550e8400-e29b-41d4-a716-446655440000"
}
```

### `GET /clip/{id}`

Opens the composer page for the given clip ID. Returns 404 if the clip has expired (> 1 hour) or never existed.

### `POST /clip/{id}/save`

Caches the selected images and saves the post to H2. Returns 422 if any image fails to cache.

**Request body**

```json
{
  "selectedImages": [
    { "src": "https://example.com/image.jpg", "alt": "A photo", "kind": "og_image", "rankOrder": 0 }
  ],
  "selectedText": "Edited body text",
  "tags": ["java", "spring"]
}
```

**Response**

```json
{ "postId": "661f9511-...", "postUrl": "/post/661f9511-..." }
```

**Error (422)**

```json
{ "error": "Image caching failed", "details": ["https://example.com/image.jpg: HTTP 403"] }
```

### `GET /`

Home page. Accepts an optional `?q=` query parameter for full-text search.

### `GET /post/{id}`

Renders the saved post page. All images are served from the local cache.

### `GET /post/{id}/edit`

Opens the edit form for the post.

### `POST /post/{id}/edit`

Saves edits to a post. Accepts and returns JSON.

**Request body**

```json
{
  "title": "Updated title",
  "selectedText": "Updated body text",
  "tags": ["java"],
  "keepImageIds": ["img-uuid-1"],
  "newImages": [
    { "src": "https://example.com/new.jpg", "alt": "", "kind": "page_image", "rankOrder": 0 }
  ]
}
```

`keepImageIds` — IDs of existing cached images to keep selected; all others are deselected.  
`newImages` — source image URLs to download, cache, and add to the post.

**Response**

```json
{ "postUrl": "/post/661f9511-..." }
```

### `POST /post/{id}/delete`

Deletes a post, its cached image rows, and its Lucene document. Underlying image files are deleted from disk too, unless their checksum is still referenced by another post. Returns 404 if the post doesn't exist.

**Response**

```json
{ "status": "deleted" }
```

### `GET /post/{id}/source-images`

Fetches and Jsoup-parses the post's original URL server-side, returning image candidates. Used by the edit page's **Fetch from source** button.

**Response**

```json
[
  { "src": "https://example.com/hero.jpg", "alt": "Hero image", "kind": "og_image", "width": null, "height": null },
  { "src": "https://example.com/photo.jpg", "alt": "A photo", "kind": "page_image", "width": 800, "height": 600 }
]
```

Returns 502 if the source page is unreachable.

### `GET /images/originals/{filename}`
### `GET /images/thumbnails/{filename}`

Serves cached image files. Responses include `Cache-Control: public, max-age=31536000, immutable`.

---

## Known limitations

- **Lazy-loaded content** — the bookmarklet collects what is present in the live DOM at click time. Content that loads later (infinite scroll, deferred images) may be missed.
- **Image hotlinking** — candidate images in the composer are loaded from their original remote URLs before save. Some CDNs block cross-origin loads; broken images are hidden automatically.
- **Popup blocker** — browsers may block the popup if the fetch takes too long. Whitelist your Clipper host in popup settings if this happens.
- **Clip TTL** — unsaved clips are stored in memory only and expire after 1 hour. Restarting the server also clears them.
- **No authentication** — any request with a valid ID can view a composer page or saved post. Do not use in a shared environment until auth is added.
