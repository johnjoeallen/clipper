package com.clipper.model;

import java.util.List;

public record SavedPost(
        String       id,
        String       clipId,
        String       url,
        String       title,
        String       ogTitle,
        String       selectedText,
        String       description,
        List<String> tags,
        String       createdAt,
        List<CachedImage> images
) {
    public String host() {
        try { return new java.net.URI(url).getHost(); }
        catch (Exception e) { return url; }
    }

    public CachedImage primaryImage() {
        return images.stream()
                .filter(i -> i.selected() && "cached".equals(i.cacheStatus()))
                .findFirst().orElse(null);
    }
}
