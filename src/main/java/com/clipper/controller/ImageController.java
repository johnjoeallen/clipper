package com.clipper.controller;

import com.clipper.config.ClipperProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class ImageController {

    private final ClipperProperties props;

    public ImageController(ClipperProperties props) {
        this.props = props;
    }

    @GetMapping("/images/originals/{filename:.+}")
    public ResponseEntity<Resource> serveOriginal(@PathVariable String filename) {
        return serve(props.getDataDir().resolve("images/originals").resolve(filename));
    }

    @GetMapping("/images/thumbnails/{filename:.+}")
    public ResponseEntity<Resource> serveThumbnail(@PathVariable String filename) {
        return serve(props.getDataDir().resolve("images/thumbnails").resolve(filename));
    }

    private ResponseEntity<Resource> serve(Path path) {
        Path base = props.getDataDir().resolve("images").normalize();
        if (!path.normalize().startsWith(base)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mimeFor(path.getFileName().toString()))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .body(new FileSystemResource(path));
    }

    private String mimeFor(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (f.endsWith(".png"))  return MediaType.IMAGE_PNG_VALUE;
        if (f.endsWith(".gif"))  return MediaType.IMAGE_GIF_VALUE;
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".avif")) return "image/avif";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
