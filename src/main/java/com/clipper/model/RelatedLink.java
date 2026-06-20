package com.clipper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RelatedLink(
        @JsonProperty("url")   String url,
        @JsonProperty("title") String title
) {}
