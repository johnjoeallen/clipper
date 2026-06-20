package com.clipper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SaveRequest(
        @JsonProperty("selectedImages") List<SelectedImage> selectedImages,
        @JsonProperty("selectedText")   String selectedText,
        @JsonProperty("tags")           List<String> tags,
        @JsonProperty("relatedLinks")   List<RelatedLink> relatedLinks
) {
    public record SelectedImage(
            @JsonProperty("src")       String src,
            @JsonProperty("alt")       String alt,
            @JsonProperty("kind")      String kind,
            @JsonProperty("rankOrder") int rankOrder
    ) {}
}
