package com.db117.learnagent.bootstrap;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.persistence.sqlite.SqliteSchemaInitializer;
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
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
            }
            // 先确认 clean-slate 标记和业务表结构，再允许其他 Repository 使用数据库。
            new SqliteSchemaInitializer(dataSource).initialize();
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("Unable to initialize the runtime database", error);
        }
    }
}
