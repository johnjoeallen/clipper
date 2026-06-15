package com.clipper.controller;

import com.clipper.cache.ImageCacheStore;
import com.clipper.model.SavedPost;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class PostController {

    private final ImageCacheStore store;

    public PostController(ImageCacheStore store) {
        this.store = store;
    }

    @GetMapping("/post/{id}")
    public String viewPost(@PathVariable String id, Model model) {
        SavedPost post = store.findPost(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        model.addAttribute("post", post);
        return "post";
    }
}
