package com.clipper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EditRequest(
        @JsonProperty("title")         String title,
        @JsonProperty("selectedText")  String selectedText,
        @JsonProperty("tags")          List<String> tags,
        @JsonProperty("keepImageIds")  List<String> keepImageIds,
        @JsonProperty("newImages")     List<SaveRequest.SelectedImage> newImages
) {}
