package com.db117.learnagent.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * 创建并验证当前 clean-slate SQLite 结构。
 *
 * <p>数据库版本不匹配时先保存本地副本，再按当前结构重新创建；不会迁移旧表。</p>
 */
public final class SqliteSchemaInitializer {
    public static final String SCHEMA_MARKER = "learn-agent-app-v2";
    public static final int SCHEMA_VERSION = 13;
    public static final String SCHEMA_SOURCE = "step-3-journey-bootstrap";

    private static final List<String> REQUIRED_TABLES = List.of(
            "schema_metadata",
            "learner",
            "learning_journey",
            "journey",
            "chapter",
            "learn_unit",
            "learning_path_item",
            "practice_task",
            "practice_attempt",
            "practice_evidence",
            "practice_assessment",
            "project",
            "project_milestone",
            "project_evidence",
            "r2_sync_configuration");

    private final DataSource dataSource;

    public SqliteSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initialize() {
        try (Connection connection = dataSource.getConnection()) {
            // 外键约束是每条 SQLite 连接的开关，初始化连接也必须显式打开。
            SqliteSupport.enableForeignKeys(connection);
            if (!tableExists(connection, "schema_metadata")) {
                if (hasBusinessTables(connection)) {
                    throw new IllegalStateException("database has no recognized v2 schema marker");
                }
                connection.setAutoCommit(false);
                createSchema(connection);
                connection.commit();
            } else {
                int version = readSchemaVersion(connection);
                if (version != SCHEMA_VERSION) {
                    backupAndRecreateSchema(connection);
                } else {
                    connection.setAutoCommit(false);
                    verifySchema(connection);
                    connection.commit();
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize the v2 SQLite schema", error);
        }
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT marker, schema_version, source FROM schema_metadata WHERE id = 1");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()
                    || !SCHEMA_MARKER.equals(result.getString("marker"))
                    || !SCHEMA_SOURCE.equals(result.getString("source"))) {
                throw new IllegalStateException("database schema marker is not recognized");
            }
            return result.getInt("schema_version");
        }
    }

    private void verifySchema(Connection connection) throws SQLException {
        verifyRequiredTables(connection);
        if (!tableExists(connection, "model_configuration")) {
            throw new IllegalStateException("recognized schema is missing table: model_configuration");
        }
    }

    private void backupAndRecreateSchema(Connection connection) throws SQLException {
        backupDatabase(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
        }
        connection.setAutoCommit(false);
        try {
            dropExistingSchema(connection);
            createSchema(connection);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        } finally {
            connection.setAutoCommit(true);
            SqliteSupport.enableForeignKeys(connection);
        }
    }

    private void backupDatabase(Connection connection) throws SQLException {
        Path databasePath = mainDatabasePath(connection);
        if (databasePath == null) {
            return;
        }

        Path backupPath = databasePath.resolveSibling(
                databasePath.getFileName() + ".backup-" + Instant.now().toEpochMilli() + ".db");
        try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, backupPath.toString());
            statement.execute();
        }
    }

    private Path mainDatabasePath(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA database_list")) {
            while (result.next()) {
                if ("main".equals(result.getString("name"))) {
                    String file = result.getString("file");
                    return file == null || file.isBlank() ? null : Path.of(file);
                }
            }
        }
        return null;
    }

    private void dropExistingSchema(Connection connection) throws SQLException {
        List<String> drops = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT type, name FROM sqlite_master "
                             + "WHERE type IN ('view', 'table') AND name NOT LIKE 'sqlite_%' "
                             + "ORDER BY CASE type WHEN 'view' THEN 0 ELSE 1 END")) {
            while (result.next()) {
                String type = result.getString("type").toUpperCase(Locale.ROOT);
                String name = result.getString("name").replace("\"", "\"\"");
                drops.add("DROP " + type + " IF EXISTS \"" + name + "\"");
            }
        }
        for (String drop : drops) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(drop);
            }
        }
    }

    private void verifyRequiredTables(Connection connection) throws SQLException {
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(connection, table)) {
                throw new IllegalStateException("recognized schema is missing table: " + table);
            }
        }
    }

    private boolean tableExists(Connection connection, String name) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasBusinessTables(Connection connection) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite_%' AND name <> 'schema_metadata' LIMIT 1")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        // 表保存 Domain 事实；Agent session、memory、plan 等 Runtime 状态不进入这里。
        execute(connection, """
                CREATE TABLE schema_metadata (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    marker TEXT NOT NULL,
                    schema_version INTEGER NOT NULL,
                    source TEXT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE learner (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    display_name TEXT NOT NULL,
                    background_summary TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE learning_journey (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    learner_id INTEGER NOT NULL REFERENCES learner(id),
                    language_pack_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
                    created_at TEXT NOT NULL,
                    completed_at TEXT,
                    CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL)
                        OR (status = 'ACTIVE' AND completed_at IS NULL))
                )
                """);
        execute(connection, """
                CREATE TABLE journey (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    portable_id TEXT NOT NULL,
                    learner_id INTEGER NOT NULL REFERENCES learner(id) ON DELETE CASCADE,
                    goal_description TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
                    created_at TEXT NOT NULL,
                    archived_at TEXT,
                    learning_journey_id INTEGER UNIQUE REFERENCES learning_journey(id) ON DELETE SET NULL,
                    is_current INTEGER NOT NULL DEFAULT 0 CHECK (is_current IN (0, 1)),
                    CHECK ((status = 'ARCHIVED' AND archived_at IS NOT NULL)
                        OR (status = 'ACTIVE' AND archived_at IS NULL)),
                    CHECK (is_current = 0 OR status = 'ACTIVE')
                )
                """);
        execute(connection, "CREATE UNIQUE INDEX uq_journey_portable_id ON journey(portable_id)");
        execute(connection, """
                CREATE UNIQUE INDEX uq_current_goal_journey
                ON journey(learner_id)
                WHERE is_current = 1
                """);
        execute(connection, """
                CREATE TABLE chapter (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    journey_id INTEGER NOT NULL REFERENCES learning_journey(id) ON DELETE CASCADE,
                    code TEXT NOT NULL,
                    title TEXT NOT NULL,
                    sequence INTEGER NOT NULL CHECK (sequence >= 0),
                    UNIQUE (journey_id, code),
                    UNIQUE (journey_id, id)
                )
                """);
        execute(connection, """
                CREATE TABLE learn_unit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    journey_id INTEGER NOT NULL REFERENCES learning_journey(id) ON DELETE CASCADE,
                    chapter_id INTEGER NOT NULL,
                    code TEXT NOT NULL,
                    title TEXT NOT NULL,
                    objective TEXT NOT NULL,
                    content TEXT NOT NULL,
                    sequence INTEGER NOT NULL CHECK (sequence >= 0),
                    prerequisite_codes TEXT NOT NULL,
                    UNIQUE (journey_id, code),
                    UNIQUE (journey_id, id),
                    FOREIGN KEY (journey_id, chapter_id) REFERENCES chapter(journey_id, id)
                )
                """);
        execute(connection, learningPathItemTableSql());
        execute(connection, """
                CREATE UNIQUE INDEX uq_current_path_item
                ON learning_path_item(journey_id)
                WHERE status = 'CURRENT'
                """);
        execute(connection, """
                CREATE TABLE practice_task (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    journey_id INTEGER NOT NULL REFERENCES learning_journey(id),
                    learn_unit_id INTEGER NOT NULL,
                    language_pack_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    difficulty INTEGER NOT NULL CHECK (difficulty >= 0),
                    starter_template TEXT NOT NULL,
                    choice_question TEXT,
                    verification_policy TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('OPEN', 'VERIFIED')),
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (journey_id, learn_unit_id) REFERENCES learn_unit(journey_id, id)
                )
                """);
        execute(connection, """
                CREATE TABLE practice_attempt (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    practice_task_id INTEGER NOT NULL REFERENCES practice_task(id) ON DELETE CASCADE,
                    submitted_at TEXT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE practice_evidence (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    attempt_id INTEGER NOT NULL REFERENCES practice_attempt(id) ON DELETE CASCADE,
                    compile_passed INTEGER NOT NULL CHECK (compile_passed IN (0, 1)),
                    tests_passed INTEGER NOT NULL CHECK (tests_passed IN (0, 1)),
                    test_count INTEGER NOT NULL CHECK (test_count >= 0),
                    lint_passed INTEGER NOT NULL CHECK (lint_passed IN (0, 1)),
                    runtime_result TEXT NOT NULL CHECK (runtime_result IN ('NOT_RUN', 'PASSED', 'FAILED')),
                    submitted_files TEXT NOT NULL,
                    verified_at TEXT,
                    choice_correct INTEGER NOT NULL CHECK (choice_correct IN (0, 1)),
                    workspace_digest TEXT NOT NULL
                )
                """);
        createPracticeAssessmentTable(connection);
        execute(connection, """
                CREATE TABLE project (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    journey_id INTEGER NOT NULL UNIQUE REFERENCES learning_journey(id),
                    title TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),
                    created_at TEXT NOT NULL,
                    completed_at TEXT,
                    CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL)
                        OR (status <> 'COMPLETED' AND completed_at IS NULL))
                )
                """);
        execute(connection, """
                CREATE TABLE project_milestone (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
                    code TEXT NOT NULL,
                    title TEXT NOT NULL,
                    sequence INTEGER NOT NULL CHECK (sequence >= 0),
                    status TEXT NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')),
                    UNIQUE (project_id, code)
                )
                """);
        execute(connection, """
                CREATE TABLE project_evidence (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    milestone_id INTEGER NOT NULL REFERENCES project_milestone(id) ON DELETE CASCADE,
                    artifact_reference TEXT NOT NULL,
                    verification_summary TEXT NOT NULL,
                    passed INTEGER NOT NULL CHECK (passed IN (0, 1)),
                    verified_at TEXT NOT NULL
                )
                """);
        createModelConfigurationTable(connection);
        createR2SyncConfigurationTable(connection);
        // Mastery 是 LearningPathItem 的只读投影，不另建可写的第二事实源。
        execute(connection, """
                CREATE VIEW mastery AS
                SELECT journey_id, learn_unit_id,
                       CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END AS mastered
                FROM learning_path_item
                """);
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_metadata(id, marker, schema_version, source) VALUES (1, ?, ?, ?)")) {
            statement.setString(1, SCHEMA_MARKER);
            statement.setInt(2, SCHEMA_VERSION);
            statement.setString(3, SCHEMA_SOURCE);
            statement.executeUpdate();
        }
    }

    private String learningPathItemTableSql() {
        return """
                CREATE TABLE learning_path_item (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    journey_id INTEGER NOT NULL REFERENCES learning_journey(id) ON DELETE CASCADE,
                    learn_unit_id INTEGER NOT NULL,
                    learn_unit_code TEXT NOT NULL,
                    sequence INTEGER NOT NULL CHECK (sequence >= 0),
                    status TEXT NOT NULL CHECK (status IN ('PENDING', 'CURRENT', 'COMPLETED', 'SKIPPED')),
                    practice_verified INTEGER NOT NULL CHECK (practice_verified IN (0, 1)),
                    assessment_id INTEGER REFERENCES practice_assessment(id),
                    pass_reason TEXT,
                    started_at TEXT,
                    completed_at TEXT,
                    updated_at TEXT NOT NULL,
                    CHECK (status <> 'COMPLETED'
                        OR ((practice_verified = 1 OR assessment_id IS NOT NULL) AND completed_at IS NOT NULL)),
                    UNIQUE (journey_id, learn_unit_id),
                    FOREIGN KEY (journey_id, learn_unit_id) REFERENCES learn_unit(journey_id, id)
                )
                """;
    }

    private void createModelConfigurationTable(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS model_configuration (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    model_name TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    api_key TEXT NOT NULL,
                    protocol TEXT NOT NULL CHECK (protocol IN ('CHAT_COMPLETIONS', 'RESPONSES'))
                )
                """);
    }

    private void createR2SyncConfigurationTable(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS r2_sync_configuration (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    endpoint TEXT NOT NULL,
                    region TEXT NOT NULL DEFAULT 'auto',
                    bucket_name TEXT NOT NULL,
                    access_key_id TEXT NOT NULL,
                    secret_access_key TEXT NOT NULL
                )
                """);
    }

    private void createPracticeAssessmentTable(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS practice_assessment (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    journey_id INTEGER NOT NULL REFERENCES learning_journey(id),
                    learn_unit_id INTEGER NOT NULL,
                    practice_task_id INTEGER NOT NULL REFERENCES practice_task(id),
                    practice_attempt_id INTEGER NOT NULL REFERENCES practice_attempt(id),
                    verdict TEXT NOT NULL CHECK (verdict IN ('READY', 'CONTINUE')),
                    rationale TEXT NOT NULL,
                    workspace_digest TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (journey_id, learn_unit_id) REFERENCES learn_unit(journey_id, id)
                )
                """);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        connection.createStatement().execute(sql);
    }
}
