package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用运行时配置。
 *
 * @param dataDir SQLite 数据目录
 * @param database SQLite 数据库文件名
 * @param userId 默认用户标识
 * @param appName ADK 应用名称
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String dataDir, String database, String userId, String appName) {
}
