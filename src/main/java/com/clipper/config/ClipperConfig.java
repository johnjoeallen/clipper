package com.clipper.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class ClipperConfig {

    @Bean
    DataSource dataSource(ClipperProperties props) throws IOException {
        Path dataDir = props.getDataDir();
        Files.createDirectories(dataDir.resolve("images/originals"));
        Files.createDirectories(dataDir.resolve("images/thumbnails"));

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dataDir.resolve("clipper.db").toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionTimeout(5_000);
        return new HikariDataSource(cfg);
    }
}
