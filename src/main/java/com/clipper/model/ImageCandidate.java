package com.clipper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageCandidate(
        @JsonProperty("src")    String src,
        @JsonProperty("alt")    String alt,
        @JsonProperty("kind")   String kind,
        @JsonProperty("width")  Integer width,
        @JsonProperty("height") Integer height
) {}
