package com.clipper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "clipper")
public class ClipperProperties {

    private Path dataDir = Path.of(System.getProperty("user.home"), ".clipper");
    private final Image image = new Image();

    public Path getDataDir() { return dataDir; }
    public void setDataDir(Path dataDir) { this.dataDir = dataDir; }
    public Image getImage() { return image; }

    public static class Image {
        private long maxBytes = 10 * 1024 * 1024L;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 30_000;
        private int maxRedirects = 5;
        private boolean allowPrivateAddresses = false;

        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
        public int getMaxRedirects() { return maxRedirects; }
        public void setMaxRedirects(int v) { this.maxRedirects = v; }
        public boolean isAllowPrivateAddresses() { return allowPrivateAddresses; }
        public void setAllowPrivateAddresses(boolean v) { this.allowPrivateAddresses = v; }
    }
}
