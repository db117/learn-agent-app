package com.example.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;

/**
 * SQLite 数据源配置。
 *
 * <p>MVP 只使用 SQLite 和 Spring JDBC；启动时创建数据目录，连接地址由 app.data-dir 和
 * app.database 组合得到。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class DatabaseConfiguration {

    /** 创建应用唯一使用的 SQLite 数据源。 */
    @Bean
    DataSource dataSource(AppProperties properties) throws IOException {
        Files.createDirectories(Path.of(properties.dataDir()));
        return new SimpleDriverDataSource(new JDBC(), "jdbc:sqlite:" + properties.database());
    }
}
