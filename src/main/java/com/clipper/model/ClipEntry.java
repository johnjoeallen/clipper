package com.clipper.model;

import java.time.Instant;

public record ClipEntry(String id, ClipPayload payload, Instant createdAt) {}
