package com.clipper.controller;

import com.clipper.cache.ImageCacheStore;
import com.clipper.model.SavedPost;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
public class PostController {

    private static final int MAX_TITLE = 500;
    private static final int MAX_TEXT  = 10_000;

    private final ImageCacheStore store;
    private final ObjectMapper    mapper;

    public PostController(ImageCacheStore store, ObjectMapper mapper) {
        this.store  = store;
        this.mapper = mapper;
    }

    @GetMapping("/post/{id}")
    public String viewPost(@PathVariable String id, Model model) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        model.addAttribute("post", post);
        return "post";
    }

    @GetMapping("/post/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        model.addAttribute("post", post);
        return "edit";
    }

    @PostMapping("/post/{id}/edit")
    public String updatePost(@PathVariable String id,
                              @RequestParam(defaultValue = "") String title,
                              @RequestParam(defaultValue = "") String selectedText,
                              @RequestParam(required = false)  String tagsJson) {
        store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        title        = title.strip();
        selectedText = selectedText.strip();
        if (title.length()        > MAX_TITLE) title        = title.substring(0, MAX_TITLE);
        if (selectedText.length() > MAX_TEXT)  selectedText = selectedText.substring(0, MAX_TEXT);

        List<String> tags = List.of();
        if (tagsJson != null && !tagsJson.isBlank()) {
            try { tags = mapper.readValue(tagsJson, new TypeReference<>() {}); }
            catch (Exception ignored) {}
        }
        tags = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.strip().toLowerCase())
                .filter(t -> t.length() <= 100)
                .distinct()
                .limit(50)
                .toList();

        store.updatePost(id, title, selectedText, tags);
        return "redirect:/post/" + id;
    }
}
