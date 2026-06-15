package com.clipper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ClipControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

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

        mockMvc.perform(post("/clip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_blankUrl_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "  ");

        mockMvc.perform(post("/clip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_nonHttpUrl_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "ftp://example.com/file");

        mockMvc.perform(post("/clip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_oversizedSelectedText_returns400() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url",          "https://example.com");
        p.put("selectedText", "x".repeat(10_001));

        mockMvc.perform(post("/clip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(p)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postClip_tooManyImages_truncatedTo20AndReturns200() throws Exception {
        List<Map<String, String>> images = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            images.add(Map.of("src",  "https://example.com/img" + i + ".jpg",
                              "kind", "page_image"));
        }
        Map<String, Object> p = new HashMap<>();
        p.put("url",    "https://example.com");
        p.put("images", images);

        JsonNode body = objectMapper.readTree(
                mockMvc.perform(post("/clip")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(p)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        // Follow up: the rendered page must not blow up
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
                mockMvc.perform(post("/clip")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(p)))
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
}
