package com.clipper.controller;

import com.clipper.model.ClipEntry;
import com.clipper.model.ClipPayload;
import com.clipper.model.ImageCandidate;
import com.clipper.store.ClipStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Controller
public class ClipController {

    private static final int MAX_URL_LEN = 2048;
    private static final int MAX_TITLE   = 500;
    private static final int MAX_TEXT    = 10_000;
    private static final int MAX_DESC    = 2000;
    private static final int MAX_IMAGES  = 20;
    private static final int MAX_IMG_SRC = 2048;

    private final ClipStore clipStore;

    public ClipController(ClipStore clipStore) {
        this.clipStore = clipStore;
    }

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

    @GetMapping("/clip/{id}")
    public String viewClip(@PathVariable String id, Model model) {
        ClipEntry entry = clipStore.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clip not found or expired"));
        model.addAttribute("clip", entry.payload());
        return "clip";
    }

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
                                img.width(),
                                img.height()))
                        .limit(MAX_IMAGES)
                        .toList();

        List<String> keywords = p.keywords() == null ? List.of() :
                p.keywords().stream()
                        .filter(k -> k != null && !k.isBlank())
                        .map(k -> k.strip().toLowerCase())
                        .filter(k -> k.length() <= 100)
                        .distinct()
                        .limit(50)
                        .toList();

        return new ClipPayload(
                p.url(),
                nvl(p.title()),
                nvl(p.selectedText()),
                nvl(p.description()),
                nvl(p.canonicalUrl()),
                nvl(p.ogTitle()),
                nvl(p.ogDescription()),
                nvl(p.ogImage()),
                images,
                keywords
        );
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
