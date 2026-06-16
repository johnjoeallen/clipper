package com.clipper.model;

public record CachedImage(
        String  id,
        String  postId,
        String  originalUrl,
        String  localPath,
        String  thumbnailPath,
        String  altText,
        Integer width,
        Integer height,
        String  contentType,
        Long    byteSize,
        String  sha256,
        String  kind,
        int     rankOrder,
        boolean selected,
        String  cachedAt,
        String  cacheStatus,
        String  cacheError
) {
    public String localFilename() {
        if (localPath == null) return null;
        return java.nio.file.Path.of(localPath).getFileName().toString();
    }

    public String thumbnailFilename() {
        if (thumbnailPath == null) return null;
        return java.nio.file.Path.of(thumbnailPath).getFileName().toString();
    }

    public String effectiveImageUrl() {
        if (!"cached".equals(cacheStatus) || localPath == null) return null;
        if (thumbnailPath != null) return "/images/thumbnails/" + thumbnailFilename();
        return "/images/originals/" + localFilename();
    }
}
