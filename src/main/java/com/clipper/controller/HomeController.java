package com.clipper.controller;

import com.clipper.cache.ImageCacheStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    private final ImageCacheStore store;

    public HomeController(ImageCacheStore store) {
        this.store = store;
    }

    @GetMapping("/")
    public String home(@RequestParam(required = false, defaultValue = "") String q,
                       Model model) {
        String trimmed = q.strip();
        if (trimmed.isEmpty()) {
            model.addAttribute("posts", store.findAllPosts());
        } else {
            model.addAttribute("posts", store.searchPosts(trimmed));
        }
        model.addAttribute("q", trimmed);
        return "home";
    }
}
