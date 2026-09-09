package com.example.agent.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

/** Creates only a new database and refuses to reinterpret an existing unknown SQLite file. */
final class DatabaseInitializer {

    static final String SCHEMA_VERSION = "agentscope-springboot-webflux-v1";
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "session", "message", "agent_run", "event", "setting", "learning_language",
            "learn_unit", "learning_journey", "learning_journey_learn_unit", "learner_profile",
            "learning_path_item", "question", "question_retirement", "assessment",
            "assessment_question", "assessment_attempt", "question_attempt", "tutor_session",
            "workflow_transition", "agent_state", "schema_metadata");

    private final DataSource dataSource;

    DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void initialize() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> tables = jdbc.queryForList(
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
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        if (!tables.contains("schema_metadata")) throw unsupportedDatabase();
        String version;
        try {
            version = jdbc.queryForObject(
                    "SELECT value FROM schema_metadata WHERE key = 'schema.version'", String.class);
        } catch (RuntimeException error) {
            throw unsupportedDatabase();
        }
        if (!SCHEMA_VERSION.equals(version) || !tables.containsAll(REQUIRED_TABLES)) {
            throw unsupportedDatabase();
        }
    }

    private IllegalStateException unsupportedDatabase() {
        return new IllegalStateException(
                "Database schema is not recognized as the AgentScope WebFlux schema; "
                        + "refusing to migrate or overwrite existing data. Use a new database path.");
    }
}
