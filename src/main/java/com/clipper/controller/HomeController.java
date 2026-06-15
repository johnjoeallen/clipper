package com.clipper.controller;

import com.clipper.cache.ImageCacheStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final ImageCacheStore store;

    public HomeController(ImageCacheStore store) {
        this.store = store;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("posts", store.findAllPosts());
        return "home";
    }
}
