package com.db117.learnagent.bootstrap;

import com.db117.learnagent.config.RuntimeConfig;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

@Startup
@ApplicationScoped
public class RuntimeInitializer {
    private final RuntimeConfig config;
    private final AgroalDataSource dataSource;

    public RuntimeInitializer(RuntimeConfig config, AgroalDataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(Path.of(config.dataDir()).resolve("db"));
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("PRAGMA foreign_keys = ON");
            }
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("Unable to initialize the runtime database", error);
        }
    }
}
