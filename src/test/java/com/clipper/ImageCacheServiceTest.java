package com.clipper;

import com.clipper.cache.ImageCacheService;
import com.clipper.cache.ImageCacheStore;
import com.clipper.config.ClipperProperties;
import com.clipper.model.CachedImage;
import com.clipper.model.SaveRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageCacheServiceTest {

    @Mock ImageCacheStore store;

    @TempDir Path tempDir;

    ClipperProperties  props;
    ImageCacheService  service;
    HttpServer         httpServer;
    String             baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        props = new ClipperProperties();
        props.setDataDir(tempDir);
        props.getImage().setAllowPrivateAddresses(true); // allow localhost in tests

        Files.createDirectories(tempDir.resolve("images/originals"));
        Files.createDirectories(tempDir.resolve("images/thumbnails"));

        service = new ImageCacheService(props, store);

        // Start embedded HTTP server on random port
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port   = httpServer.getAddress().getPort();
        baseUrl    = "http://localhost:" + port;

        // /valid.png — serves a real PNG
        httpServer.createContext("/valid.png", exchange -> {
            byte[] body = pngBytes(400, 400);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        // /large.png — sends more than maxBytes (1 byte configured below)
        httpServer.createContext("/large.png", exchange -> {
            byte[] body = pngBytes(400, 400);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        // /html — serves text/html, not an image
        httpServer.createContext("/html", exchange -> {
            byte[] body = "<html>not an image</html>".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        // /not-found — 404
        httpServer.createContext("/not-found", exchange -> {
            exchange.sendResponseHeaders(404, -1);
        });

        httpServer.start();

        // By default, store returns empty (no existing entry) for dedup checks
        when(store.findLocalPathBySha256(anyString())).thenReturn(Optional.empty());
        when(store.findThumbnailPathBySha256(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void selectedImage_isDownloadedAndCached() throws Exception {
        CachedImage result = cache(baseUrl + "/valid.png");

        assertThat(result.cacheStatus()).isEqualTo("cached");
        assertThat(result.localPath()).isNotNull();
        assertThat(Files.exists(Path.of(result.localPath()))).isTrue();
        assertThat(result.sha256()).isNotNull().hasSize(64);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.width()).isGreaterThan(0);
        assertThat(result.height()).isGreaterThan(0);
        assertThat(result.byteSize()).isGreaterThan(0);
    }

    @Test
    void duplicateImage_deduplicatedByChecksum() throws Exception {
        // First call — caches normally
        CachedImage first = cache(baseUrl + "/valid.png");
        assertThat(first.cacheStatus()).isEqualTo("cached");

        // Simulate store returning the already-cached path on the second call
        when(store.findLocalPathBySha256(first.sha256())).thenReturn(Optional.of(first.localPath()));

        // Second call with the same URL (same bytes, same sha256)
        CachedImage second = cache(baseUrl + "/valid.png");
        assertThat(second.cacheStatus()).isEqualTo("cached");
        assertThat(second.localPath()).isEqualTo(first.localPath());

        // Only one file in originals
        long fileCount = Files.list(tempDir.resolve("images/originals"))
                .filter(p -> !p.getFileName().toString().startsWith("dl-"))
                .count();
        assertThat(fileCount).isEqualTo(1);
    }

    @Test
    void invalidScheme_fileUrl_isRejected() {
        CachedImage result = cache("file:///etc/passwd");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
        assertThat(result.cacheError()).containsIgnoringCase("scheme");
    }

    @Test
    void invalidScheme_javascriptUrl_isRejected() {
        CachedImage result = cache("javascript:alert(1)");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
    }

    @Test
    void invalidScheme_dataUrl_isRejected() {
        CachedImage result = cache("data:image/png;base64,abc");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
    }

    @Test
    void oversizedImage_isRejected() {
        props.getImage().setMaxBytes(10); // 10 bytes — any real image will exceed this
        CachedImage result = cache(baseUrl + "/valid.png");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
        assertThat(result.cacheError()).containsIgnoringCase("maximum size");
    }

    @Test
    void nonImageContentType_isRejected() {
        CachedImage result = cache(baseUrl + "/html");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
        assertThat(result.cacheError()).containsIgnoringCase("Content-Type");
    }

    @Test
    void svgContentType_isRejected() throws Exception {
        httpServer.createContext("/image.svg", exchange -> {
            byte[] body = "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        CachedImage result = cache(baseUrl + "/image.svg");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
        assertThat(result.cacheError()).containsIgnoringCase("SVG");
    }

    @Test
    void privateAddress_isRejectedWhenNotAllowed() {
        props.getImage().setAllowPrivateAddresses(false);
        CachedImage result = cache("http://127.0.0.1:9999/image.png");
        assertThat(result.cacheStatus()).isEqualTo("rejected");
        assertThat(result.cacheError()).containsIgnoringCase("Private");
    }

    @Test
    void httpErrorResponse_producesFailedStatus() {
        CachedImage result = cache(baseUrl + "/not-found");
        assertThat(result.cacheStatus()).isEqualTo("failed");
        assertThat(result.cacheError()).contains("404");
    }

    @Test
    void cachedImage_thumbnailIsGenerated() throws Exception {
        CachedImage result = cache(baseUrl + "/valid.png");
        assertThat(result.cacheStatus()).isEqualTo("cached");
        assertThat(result.thumbnailPath()).isNotNull();
        assertThat(Files.exists(Path.of(result.thumbnailPath()))).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CachedImage cache(String src) {
        SaveRequest.SelectedImage img = new SaveRequest.SelectedImage(src, "alt", "page_image", 0);
        return service.cache("post-id", img, "img-id");
    }

    private byte[] pngBytes(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
