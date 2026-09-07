package com.example.agent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class DatabaseConfiguration {

  @Bean
  DataSource dataSource(AppProperties properties) throws IOException {
    Files.createDirectories(Path.of(properties.dataDir()));
    return new SimpleDriverDataSource(new JDBC(), "jdbc:sqlite:" + properties.database());
  }
}
