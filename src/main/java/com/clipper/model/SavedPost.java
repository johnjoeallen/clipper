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
) {}
