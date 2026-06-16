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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ImageCacheStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SearchIndex  searchIndex;

    public ImageCacheStore(JdbcTemplate jdbc, ObjectMapper mapper, SearchIndex searchIndex) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.searchIndex = searchIndex;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                  id            VARCHAR PRIMARY KEY,
                  clip_id       VARCHAR NOT NULL,
                  url           VARCHAR NOT NULL,
                  title         VARCHAR NOT NULL,
                  og_title      VARCHAR NOT NULL,
                  selected_text CLOB NOT NULL,
                  description   CLOB NOT NULL,
                  page_text     CLOB NOT NULL DEFAULT '',
                  tags          VARCHAR NOT NULL,
                  created_at    VARCHAR NOT NULL
                )""");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS cached_images (
                  id             VARCHAR PRIMARY KEY,
                  post_id        VARCHAR NOT NULL,
                  original_url   VARCHAR NOT NULL,
                  local_path     VARCHAR,
                  thumbnail_path VARCHAR,
                  alt_text       VARCHAR NOT NULL,
                  width          INTEGER,
                  height         INTEGER,
                  content_type   VARCHAR,
                  byte_size      BIGINT,
                  sha256         VARCHAR,
                  kind           VARCHAR NOT NULL,
                  rank_order     INTEGER NOT NULL,
                  selected       INTEGER NOT NULL DEFAULT 1,
                  cached_at      VARCHAR,
                  cache_status   VARCHAR NOT NULL,
                  cache_error    VARCHAR,
                  FOREIGN KEY (post_id) REFERENCES posts(id)
                )""");

        // Keep the Lucene index in sync with the table on every startup
        searchIndex.reindexAll(findAllPosts());
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public void savePost(SavedPost post) {
        String tagsJson;
        try { tagsJson = mapper.writeValueAsString(post.tags()); }
        catch (Exception e) { tagsJson = "[]"; }

        jdbc.update("""
                INSERT INTO posts
                  (id, clip_id, url, title, og_title, selected_text, description, page_text, tags, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                post.id(), post.clipId(), post.url(), post.title(), post.ogTitle(),
                post.selectedText(), post.description(), post.pageText(),
                tagsJson, post.createdAt());

        searchIndex.index(post);
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

    public void updatePost(String id, String title, String selectedText, List<String> tags) {
        String tagsJson;
        try { tagsJson = mapper.writeValueAsString(tags); }
        catch (Exception e) { tagsJson = "[]"; }

        jdbc.update("""
                UPDATE posts SET title = ?, og_title = '', selected_text = ?, tags = ?
                WHERE id = ?""",
                title, selectedText, tagsJson, id);

        findPost(id).ifPresent(searchIndex::index);
    }

    public void updateSelectedImages(String postId, List<String> keepIds) {
        jdbc.update("UPDATE cached_images SET selected = 0 WHERE post_id = ?", postId);
        if (!keepIds.isEmpty()) {
            String placeholders = keepIds.stream().map(s -> "?").collect(Collectors.joining(","));
            Object[] params = Stream.concat(Stream.of(postId), keepIds.stream()).toArray();
            jdbc.update("UPDATE cached_images SET selected = 1 WHERE post_id = ? AND id IN ("
                    + placeholders + ")", params);
        }
    }

    public List<CachedImage> deletePost(String id) {
        List<CachedImage> images = jdbc.query(
                "SELECT * FROM cached_images WHERE post_id = ?", this::mapImage, id);

        jdbc.update("DELETE FROM cached_images WHERE post_id = ?", id);
        jdbc.update("DELETE FROM posts WHERE id = ?", id);
        searchIndex.delete(id);

        return images;
    }

    public boolean sha256StillReferenced(String sha256) {
        List<Integer> r = jdbc.query(
                "SELECT 1 FROM cached_images WHERE sha256 = ? LIMIT 1",
                (rs, rn) -> 1, sha256);
        return !r.isEmpty();
    }

    public int maxRankOrder(String postId) {
        List<Integer> r = jdbc.query(
                "SELECT COALESCE(MAX(rank_order), -1) FROM cached_images WHERE post_id = ?",
                (rs, rn) -> rs.getInt(1), postId);
        return r.isEmpty() ? -1 : r.get(0);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<SavedPost> findPost(String id) {
        List<SavedPost> posts = jdbc.query("""
                SELECT id, clip_id, url, title, og_title, selected_text, description, page_text, tags, created_at
                FROM posts WHERE id = ?""", this::mapPost, id);

        if (posts.isEmpty()) return Optional.empty();

        List<CachedImage> images = jdbc.query("""
                SELECT * FROM cached_images WHERE post_id = ? ORDER BY rank_order""",
                this::mapImage, id);

        SavedPost p = posts.get(0);
        return Optional.of(withImages(p, images));
    }

    public List<SavedPost> findAllPosts() {
        List<SavedPost> posts = jdbc.query("""
                SELECT id, clip_id, url, title, og_title, selected_text, description, page_text, tags, created_at
                FROM posts ORDER BY created_at DESC""", this::mapPost);
        return hydrateWithImages(posts);
    }

    public List<SavedPost> searchPosts(String query) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return findAllPosts();

        List<String> ids = searchIndex.search(terms);
        if (ids.isEmpty()) return List.of();

        String ph = ids.stream().map(s -> "?").collect(Collectors.joining(","));
        List<SavedPost> posts = jdbc.query(
                "SELECT id, clip_id, url, title, og_title, selected_text, description, page_text, tags, created_at" +
                " FROM posts WHERE id IN (" + ph + ")",
                this::mapPost, ids.toArray());

        // Restore Lucene relevance order
        Map<String, SavedPost> byId = posts.stream().collect(Collectors.toMap(SavedPost::id, p -> p));
        List<SavedPost> ranked = ids.stream()
                .map(byId::get).filter(Objects::nonNull).toList();

        return hydrateWithImages(ranked);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<SavedPost> hydrateWithImages(List<SavedPost> posts) {
        if (posts.isEmpty()) return List.of();
        List<String> ids = posts.stream().map(SavedPost::id).toList();
        String ph = ids.stream().map(s -> "?").collect(Collectors.joining(","));
        List<CachedImage> images = jdbc.query(
                "SELECT * FROM cached_images WHERE cache_status = 'cached' AND post_id IN (" + ph + ") ORDER BY rank_order",
                this::mapImage, ids.toArray());
        Map<String, List<CachedImage>> byPost = images.stream().collect(Collectors.groupingBy(CachedImage::postId));
        return posts.stream()
                .map(p -> withImages(p, byPost.getOrDefault(p.id(), List.of())))
                .toList();
    }

    private static SavedPost withImages(SavedPost p, List<CachedImage> images) {
        return new SavedPost(p.id(), p.clipId(), p.url(), p.title(), p.ogTitle(),
                p.selectedText(), p.description(), p.pageText(), p.tags(), p.createdAt(), images);
    }

    private SavedPost mapPost(java.sql.ResultSet rs, int rn) throws java.sql.SQLException {
        List<String> tags;
        try { tags = mapper.readValue(rs.getString("tags"), new TypeReference<>() {}); }
        catch (Exception e) { tags = List.of(); }
        return new SavedPost(
                rs.getString("id"), rs.getString("clip_id"),
                rs.getString("url"), rs.getString("title"), rs.getString("og_title"),
                rs.getString("selected_text"), rs.getString("description"),
                nvl(rs.getString("page_text")), tags,
                rs.getString("created_at"), List.of());
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

    private static List<String> tokenize(String raw) {
        List<String> terms = new java.util.ArrayList<>();
        for (String word : raw.trim().split("\\s+")) {
            String clean = word.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(java.util.Locale.ROOT);
            if (!clean.isEmpty()) terms.add(clean);
        }
        return terms;
    }

    private static String nvl(String s)    { return s != null ? s : ""; }
    private static Integer toInt(Object o)  { return o == null ? null : ((Number) o).intValue();  }
    private static Long    toLong(Object o) { return o == null ? null : ((Number) o).longValue(); }
}
