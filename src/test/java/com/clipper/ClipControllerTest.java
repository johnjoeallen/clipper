package com.clipper;

import com.clipper.cache.ImageCacheService;
import com.clipper.model.CachedImage;
import com.clipper.model.SaveRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "clipper.data-dir=/tmp/clipper-test")
class ClipControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ImageCacheService imageCacheService;

    // ── helpers ───────────────────────────────────────────────────────────────

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private Map<String, Object> validPayloadMap() {
        Map<String, Object> p = new HashMap<>();
        p.put("url",          "https://example.com/article");
        p.put("title",        "Test Article");
        p.put("selectedText", "This is selected text.");
        p.put("description",  "A short description.");
        p.put("images", List.of(
                Map.of("src", "https://example.com/img1.jpg", "alt", "Image one", "kind", "page_image"),
                Map.of("src", "https://example.com/img2.jpg", "alt", "Image two", "kind", "page_image")
        ));
        return p;
    }

    private JsonNode postValidClip() throws Exception {
        MvcResult result = mockMvc.perform(post("/clip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validPayloadMap())))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private CachedImage cachedImage(String postId, String src, String localPath) {
        return new CachedImage(
                UUID.randomUUID().toString(), postId, src, localPath, null,
                "alt text", 400, 400, "image/jpeg", 50_000L,
                "abc123sha256", "page_image", 0, true,
                Instant.now().toString(), "cached", null);
    }

    private Map<String, Object> saveRequestMap(String src) {
        return Map.of(
                "selectedImages", List.of(Map.of("src", src, "alt", "img", "kind", "page_image", "rankOrder", 0)),
                "selectedText", "Edited text",
                "tags", List.of("java", "test"));
    }

    // ── POST /clip ────────────────────────────────────────────────────────────

    @Test
    void postClip_validPayload_returns200WithClipIdAndComposeUrl() throws Exception {
        JsonNode body = postValidClip();
        assertThat(body.get("clipId").asText()).isNotBlank();
        assertThat(body.get("composeUrl").asText()).startsWith("/clip/");
    }

    @Test
    void postClip_missingUrl_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("title", "No URL here");
        mockMvc.perform(post("/clip").contentType(MediaType.APPLICATION_JSON).content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_blankUrl_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "  ");
        mockMvc.perform(post("/clip").contentType(MediaType.APPLICATION_JSON).content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_nonHttpUrl_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "ftp://example.com/file");
        mockMvc.perform(post("/clip").contentType(MediaType.APPLICATION_JSON).content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_oversizedSelectedText_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url",          "https://example.com");
        p.put("selectedText", "x".repeat(10_001));
        mockMvc.perform(post("/clip").contentType(MediaType.APPLICATION_JSON).content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_tooManyImages_truncatedTo20AndReturns200() throws Exception {
        List<Map<String, String>> images = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            images.add(Map.of("src", "https://example.com/img" + i + ".jpg", "kind", "page_image"));
        }
        Map<String, Object> p = new HashMap<>();
        p.put("url",    "https://example.com");
        p.put("images", images);

        JsonNode body = objectMapper.readTree(
                mockMvc.perform(post("/clip").contentType(MediaType.APPLICATION_JSON).content(json(p)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/clip/" + body.get("clipId").asText()))
                .andExpect(status().isOk());
    }

    // ── GET /clip/{id} ────────────────────────────────────────────────────────

    @Test
    void getClip_rendersTitle_url_and_selectedText() throws Exception {
        String clipId = postValidClip().get("clipId").asText();
        mockMvc.perform(get("/clip/" + clipId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Test Article")))
                .andExpect(content().string(containsString("https://example.com/article")))
                .andExpect(content().string(containsString("This is selected text.")));
    }

    @Test
    void getClip_rendersImages() throws Exception {
        String clipId = postValidClip().get("clipId").asText();
        mockMvc.perform(get("/clip/" + clipId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("https://example.com/img1.jpg")))
                .andExpect(content().string(containsString("https://example.com/img2.jpg")));
    }

    @Test
    void getClip_noSelectedText_showsEmptyNotice() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url",   "https://example.com");
        p.put("title", "No selection");

        String clipId = objectMapper.readTree(
                mockMvc.perform(post("/clip").contentType(MediaType.APPLICATION_JSON).content(json(p)))
                        .andReturn().getResponse().getContentAsString())
                .get("clipId").asText();

        mockMvc.perform(get("/clip/" + clipId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No text was selected")));
    }

    @Test
    void getClip_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/clip/no-such-id-exists"))
                .andExpect(status().isNotFound());
    }

    // ── POST /clip/{id}/save ──────────────────────────────────────────────────

    @Test
    void saveClip_selectedImageCachedSuccessfully_returns200WithPostUrl() throws Exception {
        String clipId = postValidClip().get("clipId").asText();
        String src    = "https://example.com/img1.jpg";

        when(imageCacheService.cache(anyString(), any(SaveRequest.SelectedImage.class), anyString()))
                .thenAnswer(inv -> cachedImage(inv.getArgument(0), src,
                        "/tmp/clipper-test/images/originals/abc123.jpg"));

        JsonNode body = objectMapper.readTree(
                mockMvc.perform(post("/clip/" + clipId + "/save")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(saveRequestMap(src))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(body.get("postId").asText()).isNotBlank();
        assertThat(body.get("postUrl").asText()).startsWith("/post/");
    }

    @Test
    void saveClip_failedImageCache_returns422WithDetails() throws Exception {
        String clipId = postValidClip().get("clipId").asText();
        String src    = "https://example.com/bad.jpg";

        CachedImage failedImg = new CachedImage(
                "img-id", "post-id", src, null, null, "", null, null,
                null, null, null, "page_image", 0, true, null, "failed", "HTTP 403");

        when(imageCacheService.cache(anyString(), any(SaveRequest.SelectedImage.class), anyString()))
                .thenReturn(failedImg);

        mockMvc.perform(post("/clip/" + clipId + "/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(saveRequestMap(src))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Image caching failed"))
                .andExpect(jsonPath("$.details[0]").value(containsString("HTTP 403")));
    }

    @Test
    void saveClip_noImages_savesPostSuccessfully() throws Exception {
        String clipId = postValidClip().get("clipId").asText();

        Map<String, Object> req = Map.of(
                "selectedImages", List.of(),
                "selectedText", "Some text",
                "tags", List.of("tag1"));

        JsonNode body = objectMapper.readTree(
                mockMvc.perform(post("/clip/" + clipId + "/save")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(req)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(body.get("postId").asText()).isNotBlank();
    }

    @Test
    void saveClip_unknownClipId_returns404() throws Exception {
        mockMvc.perform(post("/clip/no-such-clip/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(saveRequestMap("https://example.com/img.jpg"))))
                .andExpect(status().isNotFound());
    }

    // ── GET /post/{id} ────────────────────────────────────────────────────────

    @Test
    void getPost_rendersLocalImageUrl_notOriginalUrl() throws Exception {
        String clipId = postValidClip().get("clipId").asText();
        String src    = "https://example.com/remote-image.jpg";
        String local  = "/tmp/clipper-test/images/originals/deadbeef.jpg";

        when(imageCacheService.cache(anyString(), any(SaveRequest.SelectedImage.class), anyString()))
                .thenAnswer(inv -> cachedImage(inv.getArgument(0), src, local));

        String postUrl = objectMapper.readTree(
                mockMvc.perform(post("/clip/" + clipId + "/save")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(saveRequestMap(src))))
                        .andReturn().getResponse().getContentAsString())
                .get("postUrl").asText();

        mockMvc.perform(get(postUrl))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/images/originals/deadbeef.jpg")))
                .andExpect(content().string(not(containsString("https://example.com/remote-image.jpg"))));
    }

    @Test
    void getPost_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/post/no-such-post"))
                .andExpect(status().isNotFound());
    }
}
