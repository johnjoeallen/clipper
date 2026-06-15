package com.clipper.store;

import com.clipper.model.ClipEntry;
import com.clipper.model.ClipPayload;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClipStore {

    private static final Duration TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<String, ClipEntry> store = new ConcurrentHashMap<>();

    public ClipEntry save(ClipPayload payload) {
        String id = UUID.randomUUID().toString();
        ClipEntry entry = new ClipEntry(id, payload, Instant.now());
        store.put(id, entry);
        return entry;
    }

    public Optional<ClipEntry> find(String id) {
        ClipEntry entry = store.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.createdAt().plus(TTL))) {
            store.remove(id);
            return Optional.empty();
        }
        return Optional.of(entry);
    }
}
