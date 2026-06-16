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
        cfg.setJdbcUrl("jdbc:h2:file:" + dataDir.resolve("clipperdb").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(5);
        cfg.setConnectionTimeout(5_000);
        return new HikariDataSource(cfg);
    }
}
