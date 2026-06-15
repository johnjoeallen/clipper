package com.clipper.cache;

import com.clipper.model.CachedImage;
import com.clipper.model.SavedPost;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ImageCacheStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ImageCacheStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                  id            TEXT PRIMARY KEY,
                  clip_id       TEXT NOT NULL,
                  url           TEXT NOT NULL,
                  title         TEXT NOT NULL,
                  og_title      TEXT NOT NULL,
                  selected_text TEXT NOT NULL,
                  description   TEXT NOT NULL,
                  tags          TEXT NOT NULL,
                  created_at    TEXT NOT NULL
                )""");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS cached_images (
                  id             TEXT PRIMARY KEY,
                  post_id        TEXT NOT NULL,
                  original_url   TEXT NOT NULL,
                  local_path     TEXT,
                  thumbnail_path TEXT,
                  alt_text       TEXT NOT NULL,
                  width          INTEGER,
                  height         INTEGER,
                  content_type   TEXT,
                  byte_size      INTEGER,
                  sha256         TEXT,
                  kind           TEXT NOT NULL,
                  rank_order     INTEGER NOT NULL,
                  selected       INTEGER NOT NULL DEFAULT 1,
                  cached_at      TEXT,
                  cache_status   TEXT NOT NULL,
                  cache_error    TEXT,
                  FOREIGN KEY (post_id) REFERENCES posts(id)
                )""");
    }

    public void savePost(SavedPost post) {
        String tagsJson;
        try { tagsJson = mapper.writeValueAsString(post.tags()); }
        catch (Exception e) { tagsJson = "[]"; }

        jdbc.update("""
                INSERT INTO posts
                  (id, clip_id, url, title, og_title, selected_text, description, tags, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                post.id(), post.clipId(), post.url(), post.title(), post.ogTitle(),
                post.selectedText(), post.description(), tagsJson, post.createdAt());
    }

    public void saveImage(CachedImage img) {
        jdbc.update("""
                INSERT INTO cached_images
                  (id, post_id, original_url, local_path, thumbnail_path, alt_text,
                   width, height, content_type, byte_size, sha256, kind,
                   rank_order, selected, cached_at, cache_status, cache_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                img.id(), img.postId(), img.originalUrl(), img.localPath(), img.thumbnailPath(),
                img.altText(), img.width(), img.height(), img.contentType(), img.byteSize(),
                img.sha256(), img.kind(), img.rankOrder(), img.selected() ? 1 : 0,
                img.cachedAt(), img.cacheStatus(), img.cacheError());
    }

    public Optional<SavedPost> findPost(String id) {
        List<SavedPost> posts = jdbc.query("""
                SELECT id, clip_id, url, title, og_title, selected_text, description, tags, created_at
                FROM posts WHERE id = ?""", this::mapPost, id);

        if (posts.isEmpty()) return Optional.empty();

        List<CachedImage> images = jdbc.query("""
                SELECT * FROM cached_images WHERE post_id = ? ORDER BY rank_order""",
                this::mapImage, id);

        SavedPost p = posts.get(0);
        return Optional.of(new SavedPost(p.id(), p.clipId(), p.url(), p.title(), p.ogTitle(),
                p.selectedText(), p.description(), p.tags(), p.createdAt(), images));
    }

    public void updatePost(String id, String title, String selectedText, List<String> tags) {
        String tagsJson;
        try { tagsJson = mapper.writeValueAsString(tags); }
        catch (Exception e) { tagsJson = "[]"; }
        jdbc.update("""
                UPDATE posts SET title = ?, og_title = '', selected_text = ?, tags = ?
                WHERE id = ?""",
                title, selectedText, tagsJson, id);
    }

    public List<SavedPost> findAllPosts() {
        List<SavedPost> posts = jdbc.query("""
                SELECT id, clip_id, url, title, og_title, selected_text, description, tags, created_at
                FROM posts ORDER BY created_at DESC""", this::mapPost);

        if (posts.isEmpty()) return posts;

        List<CachedImage> allImages = jdbc.query("""
                SELECT * FROM cached_images WHERE cache_status = 'cached' ORDER BY rank_order""",
                this::mapImage);

        Map<String, List<CachedImage>> byPost = allImages.stream()
                .collect(Collectors.groupingBy(CachedImage::postId));

        return posts.stream()
                .map(p -> new SavedPost(p.id(), p.clipId(), p.url(), p.title(), p.ogTitle(),
                        p.selectedText(), p.description(), p.tags(), p.createdAt(),
                        byPost.getOrDefault(p.id(), List.of())))
                .toList();
    }

    public Optional<String> findLocalPathBySha256(String sha256) {
        List<String> r = jdbc.query(
                "SELECT local_path FROM cached_images WHERE sha256 = ? AND cache_status = 'cached' LIMIT 1",
                (rs, rn) -> rs.getString("local_path"), sha256);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public Optional<String> findThumbnailPathBySha256(String sha256) {
        List<String> r = jdbc.query(
                "SELECT thumbnail_path FROM cached_images WHERE sha256 = ? AND cache_status = 'cached' LIMIT 1",
                (rs, rn) -> rs.getString("thumbnail_path"), sha256);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    private SavedPost mapPost(java.sql.ResultSet rs, int rn) throws java.sql.SQLException {
        List<String> tags;
        try { tags = mapper.readValue(rs.getString("tags"), new TypeReference<>() {}); }
        catch (Exception e) { tags = List.of(); }
        return new SavedPost(
                rs.getString("id"), rs.getString("clip_id"),
                rs.getString("url"), rs.getString("title"), rs.getString("og_title"),
                rs.getString("selected_text"), rs.getString("description"),
                tags, rs.getString("created_at"), List.of());
    }

    private CachedImage mapImage(java.sql.ResultSet rs, int rn) throws java.sql.SQLException {
        return new CachedImage(
                rs.getString("id"), rs.getString("post_id"),
                rs.getString("original_url"), rs.getString("local_path"),
                rs.getString("thumbnail_path"), rs.getString("alt_text"),
                toInt(rs.getObject("width")), toInt(rs.getObject("height")),
                rs.getString("content_type"), toLong(rs.getObject("byte_size")),
                rs.getString("sha256"), rs.getString("kind"),
                rs.getInt("rank_order"), rs.getInt("selected") != 0,
                rs.getString("cached_at"), rs.getString("cache_status"),
                rs.getString("cache_error"));
    }

    private static Integer toInt(Object o)  { return o == null ? null : ((Number) o).intValue();  }
    private static Long    toLong(Object o) { return o == null ? null : ((Number) o).longValue(); }
}
