package com.clipper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ClipPayload(
        @JsonProperty("url")           String url,
        @JsonProperty("title")         String title,
        @JsonProperty("selectedText")  String selectedText,
        @JsonProperty("description")   String description,
        @JsonProperty("canonicalUrl")  String canonicalUrl,
        @JsonProperty("ogTitle")       String ogTitle,
        @JsonProperty("ogDescription") String ogDescription,
        @JsonProperty("ogImage")       String ogImage,
        @JsonProperty("images")        List<ImageCandidate> images,
        @JsonProperty("keywords")      List<String> keywords,
        @JsonProperty("pageText")      String pageText,
        @JsonProperty("relatedLinks")  List<RelatedLink> relatedLinks
) {}
