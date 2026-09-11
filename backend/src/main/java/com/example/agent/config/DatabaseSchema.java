package com.example.agent.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SQLite 产品协议，供启动校验和快照导入校验共同使用。
 *
 * <p>该协议保持与框架无关；标记、元数据、必需表和精确版本共同构成后续实现的兼容边界。</p>
 */
final class DatabaseSchema {

    static final String MARKER = "learning-agent-sqlite";
    static final String VERSION = "2";
    static final String SOURCE_APPLICATION_ID = "learning-agent-java";
    static final Set<String> REQUIRED_METADATA_KEYS = Set.of(
            "schema.marker", "schema.version", "snapshot.created_at", "source.application_id");
    static final Set<String> REQUIRED_TABLES = Set.of(
            "session", "message", "agent_run", "event", "setting", "learning_language",
            "learn_unit", "learning_journey", "chapter", "learning_journey_learn_unit", "learner_profile",
            "learning_path_item", "question", "question_retirement", "assessment",
            "assessment_question", "assessment_attempt", "question_attempt", "tutor_session",
            "workflow_transition", "agent_state", "schema_metadata");

    private DatabaseSchema() {
    }

    /** 校验活动数据库，不执行迁移，也不静默修复。 */
    static void validate(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        if (!tables.containsAll(REQUIRED_TABLES)) throw unsupportedDatabase();
        try {
            List<String> metadataKeys = jdbc.queryForList("SELECT key FROM schema_metadata", String.class);
            if (!metadataKeys.containsAll(REQUIRED_METADATA_KEYS)) throw unsupportedDatabase();
            String marker = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'schema.marker'", String.class);
            String version = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'schema.version'", String.class);
            String source = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'source.application_id'", String.class);
            if (!MARKER.equals(marker) || !VERSION.equals(version) || !SOURCE_APPLICATION_ID.equals(source)) {
                throw unsupportedDatabase();
            }
        } catch (RuntimeException error) {
            throw unsupportedDatabase();
        }
    }

    /** 在候选文件上执行只读校验，依次检查文件头、schema、完整性和时间。 */
    static SnapshotMetadata validateSnapshot(Path path) {
        validateSqliteHeader(path);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            List<String> tables = jdbc.queryForList(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                    String.class);
            if (!tables.containsAll(REQUIRED_TABLES)) {
                throw importError("database_import_schema_unknown", "导入文件缺少当前产品 schema 所需的表。", null);
            }
            List<String> metadataKeys = jdbc.queryForList("SELECT key FROM schema_metadata", String.class);
            if (!metadataKeys.containsAll(REQUIRED_METADATA_KEYS)) {
                throw importError("database_import_schema_unknown", "导入文件缺少产品 schema 元数据。", null);
            }
            String marker = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'schema.marker'", String.class);
            if (!MARKER.equals(marker)) {
                throw importError("database_import_schema_unknown", "导入文件的 schema marker 未知。", null);
            }
            String version = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'schema.version'", String.class);
            if (!VERSION.equals(version)) {
                throw importError("database_import_schema_version", "导入文件的 schema 版本不受支持。", null);
            }
            String source = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'source.application_id'", String.class);
            if (!SOURCE_APPLICATION_ID.equals(source)) {
                throw importError("database_import_schema_unknown", "导入文件的来源应用不受支持。", null);
            }
            if (!"ok".equalsIgnoreCase(jdbc.queryForObject("PRAGMA integrity_check", String.class))) {
                throw importError("database_import_invalid_file", "SQLite 完整性校验失败。", null);
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class) != 0) {
                throw importError("database_import_invalid_file", "SQLite 外键完整性校验失败。", null);
            }
            String createdAt = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'snapshot.created_at'", String.class);
            if (createdAt == null || createdAt.isBlank()) {
                throw importError("database_import_invalid_file", "SQLite 快照时间缺失。", null);
            }
            try {
                return new SnapshotMetadata(VERSION, Instant.parse(createdAt), source);
            } catch (RuntimeException error) {
                throw importError("database_import_invalid_file", "SQLite 快照时间无效。", error);
            }
        } catch (SQLException | RuntimeException error) {
            if (error instanceof DatabaseImportException importError) throw importError;
            throw importError("database_import_invalid_file", "数据库快照校验失败。", error);
        }
    }

    /** 在 JDBC 驱动可能创建新文件之前，先拒绝非 SQLite 输入。 */
    private static void validateSqliteHeader(Path path) {
        byte[] expected = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(expected.length);
            if (!Arrays.equals(expected, header)) {
                throw importError("database_import_invalid_file", "导入文件不是 SQLite 数据库。", null);
            }
        } catch (IOException error) {
            throw importError("database_import_invalid_file", "无法读取导入文件。", error);
        }
    }

    /** 返回用于过期快照安全校验的文件数据时间戳。 */
    static Instant currentDataTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            throw new DatabaseImportException("database_import_invalid_file", "无法读取当前数据库时间。", error);
        }
    }

    private static DatabaseImportException importError(String code, String message, Throwable cause) {
        return new DatabaseImportException(code, message, cause);
    }

    /** 从已校验快照传递到导入结果和并发保护逻辑的最小元数据。 */
    record SnapshotMetadata(String schemaVersion, Instant createdAt, String sourceApplicationId) {
    }

    static IllegalStateException unsupportedDatabase() {
        return new IllegalStateException(
                "Database schema is not recognized as the learning-agent-java portable schema; "
                        + "refusing to migrate or overwrite existing data. Use a new database path.");
    }
}
