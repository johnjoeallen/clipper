package com.clipper.model;

import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class GenericTagFilter {

    private static final Logger log = LoggerFactory.getLogger(GenericTagFilter.class);
    private static final int MAX_WORDS = 5;

    private final Set<String> blocked;

    public GenericTagFilter() throws Exception {
        var words = new HashSet<String>();

        for (Object w : EnglishAnalyzer.ENGLISH_STOP_WORDS_SET) {
            words.add(new String((char[]) w));
        }
        log.info("Loaded {} Lucene stop words", words.size());

        int luceneCount = words.size();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("generic-tags.txt").getInputStream()))) {
            reader.lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .forEach(words::add);
        }
        log.info("Loaded {} words from generic-tags.txt ({} total blocked)",
                words.size() - luceneCount, words.size());

        blocked = Set.copyOf(words);
    }

    public List<String> filter(List<String> candidates) {
        var kept    = new java.util.ArrayList<String>();
        var dropped = new java.util.ArrayList<String>();

        for (String tag : candidates) {
            if (isGeneric(tag)) dropped.add(tag);
            else                kept.add(tag);
        }

        log.info("tags kept={} dropped={}", kept, dropped);
        return List.copyOf(kept);
    }

    public boolean isGeneric(String tag) {
        if (tag.split("\\s+").length > MAX_WORDS) return true;
        return blocked.contains(tag);
    }
}
