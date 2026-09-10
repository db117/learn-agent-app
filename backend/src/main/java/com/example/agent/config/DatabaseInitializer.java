package com.example.agent.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/** 只创建新数据库；发现未知的已有 SQLite 文件时拒绝重新解释。 */
final class DatabaseInitializer {

    static final String SCHEMA_VERSION = DatabaseSchema.VERSION;

    private final DataSource dataSource;

    DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 初始化或校验活动数据库，启动和导入共用同一套产品协议。 */
    void initialize() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        if (tables.isEmpty()) {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
            validate(jdbc);
            return;
        }
        validate(jdbc);
    }

    private void validate(JdbcTemplate jdbc) {
        DatabaseSchema.validate(jdbc);
    }
}
