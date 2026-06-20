package com.clipper.controller;

import com.clipper.cache.ImageCacheService;
import com.clipper.cache.ImageCacheStore;
import com.clipper.cache.PageImageFetcher;
import com.clipper.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class PostController {

    private static final int MAX_TITLE = 500;
    private static final int MAX_TEXT  = 10_000;

    private final ImageCacheStore   store;
    private final ImageCacheService cacheService;
    private final PageImageFetcher  pageFetcher;
    private final GenericTagFilter  tagFilter;

    public PostController(ImageCacheStore store,
                          ImageCacheService cacheService,
                          PageImageFetcher pageFetcher,
                          GenericTagFilter tagFilter) {
        this.store        = store;
        this.cacheService = cacheService;
        this.pageFetcher  = pageFetcher;
        this.tagFilter    = tagFilter;
    }

    // ── View ─────────────────────────────────────────────────────────────────

    @GetMapping("/post/{id}")
    public String viewPost(@PathVariable String id, Model model) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        model.addAttribute("post", post);
        return "post";
    }

    // ── Edit form ─────────────────────────────────────────────────────────────

    @GetMapping("/post/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        model.addAttribute("post", post);
        return "edit";
    }

    // ── Source image candidates (AJAX) ────────────────────────────────────────

    @GetMapping(value = "/post/{id}/source-images", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> sourceImages(@PathVariable String id) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        try {
            List<ImageCandidate> images = pageFetcher.fetchImages(post.url());
            return ResponseEntity.ok(images);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Edit save (AJAX JSON) ─────────────────────────────────────────────────

    @PostMapping(value = "/post/{id}/edit", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> updatePost(@PathVariable String id, @RequestBody EditRequest req) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        // Sanitize text fields
        String title = nvl(req.title()).strip();
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        String selectedText = nvl(req.selectedText()).strip();
        if (selectedText.length() > MAX_TEXT) selectedText = selectedText.substring(0, MAX_TEXT);

        // Sanitize tags
        List<String> tags = tagFilter.filter(
                req.tags() == null ? List.of() :
                req.tags().stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> t.strip().toLowerCase())
                        .filter(t -> t.length() <= 100)
                        .distinct().limit(50).toList());

        // Update image selection for existing cached images
        List<String> keepIds = req.keepImageIds() == null ? List.of() : req.keepImageIds();
        store.updateSelectedImages(id, keepIds);

        // Cache newly selected source images
        List<SaveRequest.SelectedImage> newImages = req.newImages() == null ? List.of() : req.newImages();
        if (!newImages.isEmpty()) {
            int baseRank = store.maxRankOrder(id) + 1;
            List<CachedImage> cached = new java.util.ArrayList<>();
            for (int i = 0; i < newImages.size(); i++) {
                SaveRequest.SelectedImage img = newImages.get(i);
                SaveRequest.SelectedImage ranked = new SaveRequest.SelectedImage(
                        img.src(), img.alt(), img.kind(), baseRank + i);
                cached.add(cacheService.cache(id, ranked, UUID.randomUUID().toString()));
            }

            List<String> errors = cached.stream()
                    .filter(c -> "failed".equals(c.cacheStatus()) || "rejected".equals(c.cacheStatus()))
                    .map(c -> c.originalUrl() + ": " + c.cacheError())
                    .toList();
            if (!errors.isEmpty()) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("error", "Image caching failed", "details", errors));
            }
            cached.forEach(store::saveImage);
        }

        List<com.clipper.model.RelatedLink> relatedLinks = sanitizeRelatedLinks(req.relatedLinks());
        store.updatePost(id, title, selectedText, tags, relatedLinks);
        return ResponseEntity.ok(Map.of("postUrl", "/post/" + id));
    }

    // ── Fetch page title (AJAX, used by related-links drop) ──────────────────

    @GetMapping(value = "/api/fetch-title", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> fetchPageTitle(@RequestParam String url) {
        if (url == null || url.isBlank() || url.length() > 2048
                || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid url"));
        }
        try {
            return ResponseEntity.ok(Map.of("title", pageFetcher.fetchTitle(url)));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("title", ""));
        }
    }

    // ── Delete (AJAX) ─────────────────────────────────────────────────────────

    @PostMapping(value = "/post/{id}/delete", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> deletePost(@PathVariable String id) {
        store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        cacheService.deletePostAndFiles(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static List<com.clipper.model.RelatedLink> sanitizeRelatedLinks(
            List<com.clipper.model.RelatedLink> raw) {
        if (raw == null) return List.of();
        var seen = new java.util.LinkedHashMap<String, com.clipper.model.RelatedLink>();
        for (var l : raw) {
            if (l == null || l.url() == null || l.url().isBlank()) continue;
            String u = l.url().strip();
            if (u.length() > 2048) continue;
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
