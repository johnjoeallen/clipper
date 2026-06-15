# Clipper

A browser bookmarklet and Spring Boot composer for clipping web pages. Click the bookmarklet on any page and Clipper captures the title, URL, Open Graph metadata, tags, selected text, and candidate images directly from the DOM — no server-side scraping required. A two-step popup composer lets you review and curate the clip before submitting.

---

## How it works

1. You click the **Clip it** bookmarklet on any page.
2. The bookmarklet injects `clipper.js` from the Clipper server into the page.
3. `clipper.js` collects metadata, tags, and images from the live DOM and POSTs them to `POST /clip`.
4. The server sanitises the payload, stores it in memory with a 1-hour TTL, and returns a `clipId` and a `composeUrl`.
5. `clipper.js` opens a popup pointing at `GET /clip/{clipId}`.
6. The composer popup opens in two steps:
   - **Step 1 — Edit:** Review and edit the captured text, manage tags, and select one or more images from the candidate grid.
   - **Step 2 — Preview:** See how the card will look, with all selected images displayed together, then submit.

---

## Features

### Metadata capture

The bookmarklet collects the following fields from every page:

| Field | Source |
|---|---|
| `url` | `window.location.href` |
| `title` | `document.title` |
| `selectedText` | Active text selection at click time |
| `description` | `<meta name="description">` |
| `canonicalUrl` | `<link rel="canonical">` |
| `ogTitle` | `<meta property="og:title">` |
| `ogDescription` | `<meta property="og:description">` |
| `ogImage` | `<meta property="og:image">` |

### Tag extraction

Tags are aggregated from multiple sources and deduplicated (lowercased, max 100 chars each, up to 50 total):

- `<meta name="keywords">`, `news_keywords`, `category`, `tags`
- `<meta property="article:section">` and repeating `article:tag`
- `<a rel~="tag">` links — covers WordPress, Baeldung, and most CMS-driven sites
- Breadcrumb navigation (`nav[aria-label*="breadcrumb"]`, `.breadcrumb a`, `#wayfinding-breadcrumbs_feature_div a`, and Schema.org microdata) — covers Amazon and most e-commerce / news sites that skip meta keywords
- Schema.org JSON-LD blocks: `keywords`, `articleSection`, and `about[].name`

### Image collection and filtering

Images are collected from the OG image tag (highest priority) and all `<img src>` elements on the page. Images are filtered aggressively:

- At collection time: any image with a known dimension below **300px** is dropped before the payload is sent to the server.
- After rendering in the composer grid: the `onload` handler checks true natural dimensions and hides any image below **300px** in either dimension.
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
- Back button to return and adjust; Submit button to post the clip

### Theme

The composer supports a dark and light theme with a toggle button in the header. The preference is persisted in `localStorage`.

### Full-text index data

Every composer page includes a hidden `#clip-index-data` element containing all text fields (title, OG title, description, OG description, selected text, tags, and image alt texts) for full-text search indexing. URLs are available as `data-url` and `data-canonical` attributes.

---

## Stack

| Layer | Technology |
|---|---|
| Server | Spring Boot 3.3 · Java 17 |
| Templates | Thymeleaf |
| Bookmarklet | Vanilla JS (ES5, no dependencies) |
| Storage | In-memory `ConcurrentHashMap` with 1-hour TTL |
| Tests | JUnit 5 + MockMvc |

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

## Running the tests

```bash
mvn test
```

Tests use JUnit 5 and MockMvc and do not require a running server.

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

---

## Known limitations

- **DOM-only capture** — the bookmarklet collects what is present in the loaded DOM. Lazy-loaded or JS-rendered content that has not yet appeared may be missed.
- **Image hotlinking** — some CDNs block cross-origin image loads. Broken images are hidden automatically but cannot be recovered.
- **Popup blocker** — browsers may block the popup if the fetch takes too long. Whitelist your Clipper host in popup settings if this happens.
- **No persistence** — clips are stored in memory only. Restarting the server clears all clips. Selected image state is client-side and is not saved.
- **No authentication** — any request with a valid `clipId` can view the composer. Do not use in a shared environment until auth is added.
- **No final submit** — the composer is display-only at this stage. Posting the clip to an external service is planned for a future stage.
