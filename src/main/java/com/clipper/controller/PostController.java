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

    public PostController(ImageCacheStore store,
                          ImageCacheService cacheService,
                          PageImageFetcher pageFetcher) {
        this.store        = store;
        this.cacheService = cacheService;
        this.pageFetcher  = pageFetcher;
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
        List<String> tags = req.tags() == null ? List.of() :
                req.tags().stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> t.strip().toLowerCase())
                        .filter(t -> t.length() <= 100)
                        .distinct().limit(50).toList();

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

        store.updatePost(id, title, selectedText, tags);
        return ResponseEntity.ok(Map.of("postUrl", "/post/" + id));
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
