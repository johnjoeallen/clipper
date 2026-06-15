# Backbrowse Clipper — Stage 1

## What Stage 1 implements

A browser bookmarklet + Spring Boot 3 web app that lets you capture a page from your
browser and open a composer popup without any server-side scraping.

**Flow**

1. You click the bookmarklet on any page.
2. The bookmarklet injects `clipper.js` from the clipper app.
3. `clipper.js` collects the page URL, title, selected text, meta/OG fields, and
   candidate images from the DOM, then POSTs them to `POST /clip`.
4. The server stores the payload in memory (1-hour TTL) and returns a `clipId` and
   a `composeUrl`.
5. `clipper.js` opens a popup pointing at `GET /clip/{clipId}`.
6. The popup shows the captured data and an image grid where you can choose a primary
   image. The preview card updates live.

---

## How to run

```bash
cd /path/to/clipper
mvn spring-boot:run
```

The app listens on `http://localhost:8080` by default.

---

## Installing the bookmarklet

### Development (localhost)

Create a new browser bookmark with the following URL:

```
javascript:(()=>{const s=document.createElement('script');s.src='http://localhost:8080/clipper.js';document.body.appendChild(s);})();
```

Give it a name like **"Clip it"** and add it to your bookmarks bar.

### Production / other host

Replace `http://localhost:8080` with your deployed app's base URL.  Because `clipper.js`
derives the app origin from its own `<script src>`, no other changes are needed — the
same script handles the POST and the popup URL automatically.

---

## How to use

1. Navigate to any page you want to clip.
2. Optionally select some text.
3. Click the **"Clip it"** bookmarklet.
4. A popup composer opens. It shows:
   - The page title and URL.
   - The text you selected (or a notice if nothing was selected).
   - The meta/OG description if present.
   - A grid of candidate images from the page.
5. Click an image to set it as the primary image. The preview card at the bottom
   updates immediately.
6. The "View original →" link opens the source page in a new tab.

---

## Running the tests

```bash
mvn test
```

Tests use JUnit 5 + MockMvc and do not require a running server.

---

## Known limitations (Stage 1)

- **DOM-only capture** — the bookmarklet can only collect what is already present in
  the loaded DOM. Lazy-loaded images or JS-rendered content that hasn't been scrolled
  into view may be missed.

- **Image hotlinking / CORS / CDN restrictions** — some site CDNs block cross-origin
  image loads, so images in the composer may appear broken. This is a browser
  same-origin restriction on the image itself, not a bug in the app. Failed images are
  automatically hidden (`onerror` handler).

- **Popup blocker** — browsers may block the popup if the `fetch` takes too long after
  the bookmarklet click. Whitelist `localhost:8080` (or your host) in your browser's
  popup settings if this happens.

- **No persistence** — the captured payload is stored in memory on the server with a
  1-hour TTL. Restarting the server clears all clips. Selected image state is
  client-side only and is not saved.

- **No authentication** — any request with a valid `clipId` can view the composer page.
  Do not use this for sensitive data in a shared environment until auth is wired in.

- **No server-side scraping** — this stage deliberately avoids fetching the original
  page from the server. All captured data comes from the browser.

- **No final post/save** — the composer is display-only in Stage 1. Saving a clip as a
  Backbrowse link requires a future stage.
