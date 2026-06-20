package com.clipper.cache;

import com.clipper.config.ClipperProperties;
import com.clipper.model.ImageCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PageImageFetcher {

    private static final int    MAX_HTML_BYTES = 5 * 1024 * 1024;
    private static final int    MAX_RESULTS    = 30;
    private static final Set<String> ALLOWED   = Set.of("http", "https");

    private final ClipperProperties props;

    public PageImageFetcher(ClipperProperties props) {
        this.props = props;
    }

    public List<ImageCandidate> fetchImages(String pageUrl) throws IOException {
        Document doc = fetchDocument(pageUrl);

        List<ImageCandidate> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Element ogImg = doc.selectFirst("meta[property=og:image]");
        if (ogImg != null) {
            String src = ogImg.attr("abs:content");
            if (src.isBlank()) src = ogImg.attr("content");
            if (isHttp(src) && seen.add(src)) {
                results.add(new ImageCandidate(src, "", "og_image", null, null));
            }
        }

        for (Element img : doc.select("img[src]")) {
            if (results.size() >= MAX_RESULTS) break;
            String src = img.absUrl("src");
            if (src.isBlank() || !isHttp(src) || !seen.add(src)) continue;
            String alt = img.attr("alt");
            results.add(new ImageCandidate(src, alt, "page_image",
                    parseIntOrNull(img.attr("width")),
                    parseIntOrNull(img.attr("height"))));
        }

        return results;
    }

    public String fetchTitle(String pageUrl) throws IOException {
        Document doc = fetchDocument(pageUrl);
        String title = doc.title().trim();
        if (title.isEmpty()) {
            Element og = doc.selectFirst("meta[property=og:title]");
            if (og != null) title = og.attr("content").trim();
        }
        return title;
    }

    private Document fetchDocument(String pageUrl) throws IOException {
        URI uri;
        try { uri = new URI(pageUrl).normalize(); }
        catch (URISyntaxException e) { throw new IOException("Invalid URL: " + e.getMessage()); }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED.contains(scheme.toLowerCase())) {
            throw new IOException("URL scheme not allowed: " + scheme);
        }

        if (!props.getImage().isAllowPrivateAddresses()) {
            String host = uri.getHost();
            if (host != null) {
                try {
                    for (InetAddress a : InetAddress.getAllByName(host)) {
                        if (isPrivate(a)) throw new IOException("Private address blocked");
                    }
                } catch (UnknownHostException e) {
                    throw new IOException("Cannot resolve host: " + host);
                }
            }
        }

        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(props.getImage().getConnectTimeoutMs());
        conn.setReadTimeout(props.getImage().getReadTimeoutMs());
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Clipper/1.0");
        conn.setRequestProperty("Accept", "text/html,*/*");

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status);

        byte[] raw;
        try (InputStream in = conn.getInputStream()) {
            raw = in.readNBytes(MAX_HTML_BYTES);
        }

        return Jsoup.parse(new String(raw, StandardCharsets.UTF_8), pageUrl);
    }

    private boolean isHttp(String url) {
        if (url == null || url.isBlank()) return false;
        String l = url.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() ||
                a.isSiteLocalAddress() || a.isAnyLocalAddress() ||
                a.isMulticastAddress()) return true;
        byte[] raw = a.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF, b1 = raw[1] & 0xFF;
            if (b0 == 100 && (b1 & 0xC0) == 64) return true;
            if (b0 == 0) return true;
        }
        return false;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
