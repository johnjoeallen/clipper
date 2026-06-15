package com.clipper.cache;

import com.clipper.config.ClipperProperties;
import com.clipper.model.CachedImage;
import com.clipper.model.SaveRequest;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class ImageCacheService {

    private static final Set<String> ALLOWED_SCHEMES     = Set.of("http", "https");
    private static final Set<String> REJECTED_MIME_TYPES = Set.of("image/svg+xml");
    private static final int         THUMBNAIL_PX        = 400;

    private final ClipperProperties props;
    private final ImageCacheStore    store;

    public ImageCacheService(ClipperProperties props, ImageCacheStore store) {
        this.props = props;
        this.store = store;
    }

    public CachedImage cache(String postId, SaveRequest.SelectedImage req, String imageId) {
        String src = req.src();

        URI uri;
        try { uri = new URI(src).normalize(); }
        catch (URISyntaxException e) {
            return rejected(imageId, postId, src, req, "Invalid URL: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            return rejected(imageId, postId, src, req, "URL scheme not allowed: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return rejected(imageId, postId, src, req, "Missing host in URL");
        }

        if (!props.getImage().isAllowPrivateAddresses()) {
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    if (isPrivateAddress(addr)) {
                        return rejected(imageId, postId, src, req,
                                "Private or reserved address not allowed: " + addr.getHostAddress());
                    }
                }
            } catch (UnknownHostException e) {
                return rejected(imageId, postId, src, req, "Cannot resolve host: " + host);
            }
        }

        Path originalsDir = props.getDataDir().resolve("images/originals");
        Path tempFile = null;

        try {
            tempFile = Files.createTempFile(originalsDir, "dl-", ".tmp");

            HttpURLConnection conn = openConnection(uri);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return failed(imageId, postId, src, req, "HTTP " + status);
            }

            String contentType = normalizeContentType(conn.getContentType());
            if (contentType == null || !contentType.startsWith("image/")) {
                return rejected(imageId, postId, src, req,
                        "Content-Type is not an image: " + contentType);
            }
            if (REJECTED_MIME_TYPES.contains(contentType)) {
                return rejected(imageId, postId, src, req, "SVG images are not accepted");
            }

            long maxBytes = props.getImage().getMaxBytes();
            String sha256;
            long byteSize;

            try (InputStream in  = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(tempFile)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buf  = new byte[8192];
                long   total = 0;
                int    read;
                while ((read = in.read(buf)) != -1) {
                    if (total + read > maxBytes) {
                        return rejected(imageId, postId, src, req,
                                "Image exceeds maximum size of " + maxBytes + " bytes");
                    }
                    digest.update(buf, 0, read);
                    out.write(buf, 0, read);
                    total += read;
                }
                byteSize = total;
                sha256   = bytesToHex(digest.digest());
            }

            // Deduplicate by checksum
            Optional<String> existingPath  = store.findLocalPathBySha256(sha256);
            Optional<String> existingThumb = store.findThumbnailPathBySha256(sha256);
            if (existingPath.isPresent()) {
                Files.deleteIfExists(tempFile);
                tempFile = null;
                int[] dims = readDimensions(Path.of(existingPath.get()));
                return new CachedImage(imageId, postId, src,
                        existingPath.get(), existingThumb.orElse(null),
                        nvl(req.alt()), dims[0] > 0 ? dims[0] : null, dims[1] > 0 ? dims[1] : null,
                        contentType, byteSize, sha256, nvl(req.kind()), req.rankOrder(), true,
                        Instant.now().toString(), "cached", null);
            }

            // Validate by decoding
            BufferedImage img;
            try { img = ImageIO.read(tempFile.toFile()); }
            catch (Exception e) {
                return rejected(imageId, postId, src, req, "Cannot decode image: " + e.getMessage());
            }
            if (img == null) {
                return rejected(imageId, postId, src, req, "File is not a recognizable image format");
            }
            int width  = img.getWidth();
            int height = img.getHeight();

            String ext      = extensionFor(contentType);
            String filename = sha256 + "." + ext;
            Path   finalPath = originalsDir.resolve(filename);
            Files.move(tempFile, finalPath, StandardCopyOption.ATOMIC_MOVE);
            tempFile = null;

            // Thumbnail (non-fatal if it fails)
            String thumbnailPath = null;
            try {
                Path thumbFile = props.getDataDir().resolve("images/thumbnails").resolve(filename);
                Thumbnails.of(finalPath.toFile())
                        .size(THUMBNAIL_PX, THUMBNAIL_PX)
                        .keepAspectRatio(true)
                        .toFile(thumbFile.toFile());
                thumbnailPath = thumbFile.toString();
            } catch (Exception ignored) {}

            return new CachedImage(imageId, postId, src,
                    finalPath.toString(), thumbnailPath,
                    nvl(req.alt()), width, height, contentType, byteSize, sha256,
                    nvl(req.kind()), req.rankOrder(), true,
                    Instant.now().toString(), "cached", null);

        } catch (Exception e) {
            return failed(imageId, postId, src, req, e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    // ── HTTP with manual redirect following (validates each hop) ─────────────

    private HttpURLConnection openConnection(URI start) throws IOException {
        URI  uri = start;
        int  max = props.getImage().getMaxRedirects();
        HttpURLConnection conn = null;

        for (int hop = 0; hop <= max; hop++) {
            if (conn != null) conn.disconnect();
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(props.getImage().getConnectTimeoutMs());
            conn.setReadTimeout(props.getImage().getReadTimeoutMs());
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Clipper/1.0");

            int status = conn.getResponseCode();
            if (status < 300 || status >= 400) return conn;

            String location = conn.getHeaderField("Location");
            if (location == null) return conn;

            URI next;
            try { next = uri.resolve(location); }
            catch (Exception e) { throw new IOException("Invalid redirect location: " + location); }

            String scheme = next.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new IOException("Redirect to disallowed scheme: " + scheme);
            }
            if (!props.getImage().isAllowPrivateAddresses()) {
                String rHost = next.getHost();
                if (rHost != null) {
                    try {
                        for (InetAddress a : InetAddress.getAllByName(rHost)) {
                            if (isPrivateAddress(a)) {
                                throw new IOException("Redirect to private address blocked");
                            }
                        }
                    } catch (UnknownHostException e) {
                        throw new IOException("Cannot resolve redirect host: " + rHost);
                    }
                }
            }
            uri = next;
        }
        throw new IOException("Too many redirects");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isPrivateAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
                addr.isSiteLocalAddress() || addr.isAnyLocalAddress() ||
                addr.isMulticastAddress()) return true;

        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // 100.64.0.0/10 (CGNAT / shared address space)
            if (b0 == 100 && (b1 & 0xC0) == 64) return true;
            // 0.0.0.0/8
            if (b0 == 0) return true;
        }
        return false;
    }

    private String normalizeContentType(String ct) {
        if (ct == null) return null;
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase();
    }

    private String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png"  -> "png";
            case "image/gif"  -> "gif";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "image/bmp"  -> "bmp";
            case "image/tiff" -> "tiff";
            default           -> "bin";
        };
    }

    private int[] readDimensions(Path path) {
        try {
            BufferedImage img = ImageIO.read(path.toFile());
            if (img != null) return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception ignored) {}
        return new int[]{0, 0};
    }

    private CachedImage rejected(String id, String postId, String src,
                                  SaveRequest.SelectedImage req, String reason) {
        return new CachedImage(id, postId, src, null, null, nvl(req.alt()), null, null,
                null, null, null, nvl(req.kind()), req.rankOrder(), true,
                null, "rejected", reason);
    }

    private CachedImage failed(String id, String postId, String src,
                                SaveRequest.SelectedImage req, String reason) {
        return new CachedImage(id, postId, src, null, null, nvl(req.alt()), null, null,
                null, null, null, nvl(req.kind()), req.rankOrder(), true,
                null, "failed", reason);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
