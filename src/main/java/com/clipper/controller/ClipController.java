package com.clipper.controller;

import com.clipper.cache.ImageCacheService;
import com.clipper.cache.ImageCacheStore;
import com.clipper.model.*;
import com.clipper.store.ClipStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class ClipController {

    private static final int MAX_URL_LEN   = 2048;
    private static final int MAX_TITLE     = 500;
    private static final int MAX_TEXT      = 10_000;
    private static final int MAX_DESC      = 2000;
    private static final int MAX_PAGE_TEXT = 100_000;
    private static final int MAX_IMAGES    = 20;
    private static final int MAX_IMG_SRC   = 2048;

    private final ClipStore        clipStore;
    private final ImageCacheService imageCacheService;
    private final ImageCacheStore   imageCacheStore;
    private final GenericTagFilter  tagFilter;

    public ClipController(ClipStore clipStore,
                          ImageCacheService imageCacheService,
                          ImageCacheStore imageCacheStore,
                          GenericTagFilter tagFilter) {
        this.clipStore         = clipStore;
        this.imageCacheService = imageCacheService;
        this.imageCacheStore   = imageCacheStore;
        this.tagFilter         = tagFilter;
    }

    // ── POST /clip ────────────────────────────────────────────────────────────

    @CrossOrigin(origins = "*")
    @PostMapping(value = "/clip", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> createClip(@RequestBody ClipPayload payload) {
        if (payload == null || payload.url() == null || payload.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        if (payload.url().length() > MAX_URL_LEN) {
            return ResponseEntity.badRequest().body(Map.of("error", "url exceeds maximum length"));
        }
        if (!payload.url().startsWith("http://") && !payload.url().startsWith("https://")) {
            return ResponseEntity.badRequest().body(Map.of("error", "url must use http or https"));
        }
        if (payload.title() != null && payload.title().length() > MAX_TITLE) {
            return ResponseEntity.badRequest().body(Map.of("error", "title exceeds maximum length"));
        }
        if (payload.selectedText() != null && payload.selectedText().length() > MAX_TEXT) {
            return ResponseEntity.badRequest().body(Map.of("error", "selectedText exceeds maximum length"));
        }
        if (payload.description() != null && payload.description().length() > MAX_DESC) {
            return ResponseEntity.badRequest().body(Map.of("error", "description exceeds maximum length"));
        }

        ClipEntry entry = clipStore.save(sanitize(payload));
        return ResponseEntity.ok(Map.of(
                "clipId",     entry.id(),
                "composeUrl", "/clip/" + entry.id()
        ));
    }

    // ── GET /clip/quick ───────────────────────────────────────────────────────
    // Fallback for CSP-restricted pages that block script injection or fetch.
    // Accepts minimal data via query params and redirects to the compose page.

    @GetMapping("/clip/quick")
    public String quickClip(
            @RequestParam String url,
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "") String ogTitle,
            @RequestParam(defaultValue = "") String desc,
            @RequestParam(defaultValue = "") String kw,
            @RequestParam(defaultValue = "") String cu,
            @RequestParam(required = false) List<String> img) {

        if (url == null || url.isBlank() || url.length() > MAX_URL_LEN
                || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid url");
        }

        String safeTitle   = title.length()   > MAX_TITLE ? title.substring(0, MAX_TITLE)  : title;
        String safeText    = text.length()    > MAX_TEXT  ? text.substring(0, MAX_TEXT)     : text;
        String safeOgTitle = ogTitle.length() > MAX_TITLE ? ogTitle.substring(0, MAX_TITLE) : ogTitle;
        String safeDesc    = desc.length()    > MAX_DESC  ? desc.substring(0, MAX_DESC)     : desc;
        String safeCu      = cu.length()      > MAX_URL_LEN ? "" : cu;

        List<ImageCandidate> images = img == null ? List.of() :
                img.stream()
                        .filter(u -> u != null && !u.isBlank()
                                && u.length() <= MAX_IMG_SRC
                                && (u.startsWith("http://") || u.startsWith("https://")))
                        .distinct()
                        .limit(MAX_IMAGES)
                        .map(u -> new ImageCandidate(u, "", "og_image", null, null))
                        .collect(Collectors.toList());

        String ogImageStr = images.isEmpty() ? "" : images.get(0).src();

        List<String> keywords = kw.isBlank() ? List.of() :
                tagFilter.filter(
                        Arrays.stream(kw.split(","))
                                .map(String::strip)
                                .filter(k -> !k.isBlank() && k.length() <= 100)
                                .distinct()
                                .limit(50)
                                .collect(Collectors.toList()));

        ClipPayload payload = new ClipPayload(
                url, safeTitle, safeText, safeDesc, safeCu,
                safeOgTitle, "", ogImageStr,
                images, keywords, "", List.of());
        ClipEntry entry = clipStore.save(payload);
        return "redirect:/clip/" + entry.id();
    }

    // ── GET /clip/{id} ────────────────────────────────────────────────────────

    @GetMapping("/clip/{id}")
    public String viewClip(@PathVariable String id, Model model) {
        ClipEntry entry = clipStore.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Clip not found or expired"));
        model.addAttribute("clip", entry.payload());
        model.addAttribute("clipId", id);
        return "clip";
    }

    // ── POST /clip/{id}/save ──────────────────────────────────────────────────

    @CrossOrigin(origins = "*")
    @PostMapping(value = "/clip/{id}/save", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> saveClip(@PathVariable String id, @RequestBody SaveRequest req) {
        ClipEntry entry = clipStore.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Clip not found or expired"));

        String postId = UUID.randomUUID().toString();
        List<SaveRequest.SelectedImage> selected =
                req.selectedImages() == null ? List.of() : req.selectedImages();

        List<CachedImage> cached = new ArrayList<>();
        for (SaveRequest.SelectedImage img : selected) {
            cached.add(imageCacheService.cache(postId, img, UUID.randomUUID().toString()));
        }

        List<String> errors = cached.stream()
                .filter(c -> "failed".equals(c.cacheStatus()) || "rejected".equals(c.cacheStatus()))
                .map(c -> c.originalUrl() + ": " + c.cacheError())
                .toList();
        if (!errors.isEmpty()) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", "Image caching failed", "details", errors));
        }

        List<String> tags = tagFilter.filter(
                req.tags() == null ? List.of() :
                req.tags().stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> t.strip().toLowerCase())
                        .filter(t -> t.length() <= 100)
                        .distinct()
                        .limit(50)
                        .toList());

        List<com.clipper.model.RelatedLink> relatedLinks = sanitizeRelatedLinks(req.relatedLinks());

        ClipPayload p = entry.payload();
        String selectedText = req.selectedText() != null ? req.selectedText() : p.selectedText();

        String now = Instant.now().toString();
        SavedPost post = new SavedPost(postId, id, p.url(), p.title(), p.ogTitle(),
                selectedText, p.description(), nvl(p.pageText()), tags, now, now,
                cached, relatedLinks);

        imageCacheStore.savePost(post);
        cached.forEach(imageCacheStore::saveImage);

        return ResponseEntity.ok(Map.of(
                "postId",  postId,
                "postUrl", "/post/" + postId
        ));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClipPayload sanitize(ClipPayload p) {
        List<ImageCandidate> images = p.images() == null ? List.of() :
                p.images().stream()
                        .filter(img -> img != null
                                && img.src() != null
                                && !img.src().isBlank()
                                && img.src().length() <= MAX_IMG_SRC
                                && (img.src().startsWith("http://")
                                        || img.src().startsWith("https://")
                                        || img.src().startsWith("//")))
                        .map(img -> new ImageCandidate(
                                img.src(),
                                img.alt()  != null ? img.alt()  : "",
                                img.kind() != null ? img.kind() : "",
                                img.width(), img.height()))
                        .limit(MAX_IMAGES)
                        .toList();

        List<String> keywords = tagFilter.filter(
                p.keywords() == null ? List.of() :
                p.keywords().stream()
                        .filter(k -> k != null && !k.isBlank())
                        .map(k -> k.strip().toLowerCase())
                        .filter(k -> k.length() <= 100)
                        .distinct()
                        .limit(50)
                        .toList());

        String pageText = nvl(p.pageText());
        if (pageText.length() > MAX_PAGE_TEXT) pageText = pageText.substring(0, MAX_PAGE_TEXT);

        List<com.clipper.model.RelatedLink> relatedLinks = sanitizeRelatedLinks(p.relatedLinks());

        return new ClipPayload(p.url(), nvl(p.title()), nvl(p.selectedText()),
                nvl(p.description()), nvl(p.canonicalUrl()), nvl(p.ogTitle()),
                nvl(p.ogDescription()), nvl(p.ogImage()), images, keywords, pageText, relatedLinks);
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static List<com.clipper.model.RelatedLink> sanitizeRelatedLinks(
            List<com.clipper.model.RelatedLink> raw) {
        if (raw == null) return List.of();
        var seen = new java.util.LinkedHashMap<String, com.clipper.model.RelatedLink>();
        for (var l : raw) {
            if (l == null || l.url() == null || l.url().isBlank()) continue;
            String u = l.url().strip();
            if (u.length() > MAX_URL_LEN) continue;
            if (!u.startsWith("http://") && !u.startsWith("https://")) continue;
            if (!seen.containsKey(u)) {
                String t = l.title() != null ? l.title().strip() : "";
                if (t.length() > 200) t = t.substring(0, 200);
                seen.put(u, new com.clipper.model.RelatedLink(u, t));
            }
            if (seen.size() >= 20) break;
        }
        return List.copyOf(seen.values());
    }
}
