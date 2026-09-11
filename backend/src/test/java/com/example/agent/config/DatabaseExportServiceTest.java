package com.example.agent.config;

import com.example.agent.api.DatabaseController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsAnIndependentValidSqliteFileThroughWebFlux() throws Exception {
        Path activePath = tempDir.resolve("active.db");
        DataSource active = dataSource(activePath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(active);
        JdbcClient activeJdbc = JdbcClient.create(active);
        activeJdbc.sql("INSERT INTO learning_language VALUES ('lang-1', 'python', 'Python', 'Python', 1)").update();
        activeJdbc.sql("INSERT INTO learning_journey VALUES ('journey-1', 'user-1', 'python', 'Learn Python', 'ACTIVE', 'now', 'now')").update();
        activeJdbc.sql("INSERT INTO chapter VALUES ('chapter-1', 'journey-1', 'python.basics-chapter', 'Basics', 'Basics', 1, '[]')").update();
        activeJdbc.sql("INSERT INTO learn_unit VALUES ('unit-1', 'python', 'python.basics', 'python.basics-chapter', 'Basics', 'Basics', 1, '[]', 80, NULL, 1, '[]', 'Intro', '[]', '[]', 1, '', 0, '', '[]', '')").update();
        activeJdbc.sql("INSERT INTO learning_journey_learn_unit VALUES ('journey-1', 'python.basics')").update();
        activeJdbc.sql("INSERT INTO learner_profile VALUES ('journey-1', '中文', 1, 'beginner', 'learn')").update();
        activeJdbc.sql("INSERT INTO learning_path_item (id, journey_id, learn_unit_code, sequence, status) VALUES ('path-1', 'journey-1', 'python.basics', 1, 'CURRENT')").update();
        activeJdbc.sql("INSERT INTO question (id, learn_unit_code, chapter_code, type, difficulty, prompt, points, config_json, rubric_json, language, starter_code, reference_concepts_json, diagnostic_eligible, role) VALUES ('question-1', 'python.basics', NULL, 'MULTIPLE_CHOICE', 1, 'What?', 10, '{}', NULL, NULL, NULL, NULL, 1, 'DIAGNOSTIC')").update();
        activeJdbc.sql("INSERT INTO assessment (id, journey_id, learn_unit_code, chapter_code, type, status, created_at, completed_at) VALUES ('assessment-1', 'journey-1', 'python.basics', NULL, 'LEARN_UNIT', 'CREATED', 'now', NULL)").update();
        activeJdbc.sql("INSERT INTO assessment_question VALUES ('assessment-1', 'question-1', 1)").update();
        activeJdbc.sql("INSERT INTO assessment_attempt VALUES ('attempt-1', 'assessment-1', 'journey-1', 'python.basics', 1, 10, NULL, 10, 1, 'now', 'now')").update();
        activeJdbc.sql("INSERT INTO question_attempt VALUES ('question-1', 'attempt-1', '{}', 10, 10, 'good', 1, NULL, NULL, NULL)").update();
        activeJdbc.sql("INSERT INTO session VALUES ('session-1', 'user-1', 'Tutor', 'now', 'now')").update();
        activeJdbc.sql("INSERT INTO message VALUES ('message-1', 'session-1', 'user', 'Hello', 'now')").update();
        activeJdbc.sql("INSERT INTO event (id, session_id, run_id, author, event_type, content, timestamp, raw_json) VALUES ('event-1', 'session-1', 'run-1', 'user', 'text_delta', 'Hello', 'now', '{}')").update();
        activeJdbc.sql("INSERT INTO tutor_session VALUES ('tutor-1', 'journey-1', 'python.basics', 'session-1')").update();
        activeJdbc.sql("INSERT INTO workflow_transition VALUES ('transition-1', 'journey-1', NULL, 'start', 'CURRENT', '{}', 'now')").update();
        activeJdbc.sql("INSERT INTO agent_state VALUES ('user-1', 'session-1', 'state', 'test', '{}', 1, 'now')").update();
        activeJdbc.sql("INSERT INTO setting (key, value, updated_at) VALUES ('test.key', 'before', 'now')").update();

        WebTestClient client = WebTestClient.bindToController(new DatabaseController(new DatabaseExportService(active)))
                .configureClient()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        byte[] snapshot = client.get().uri("/api/database/export")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectHeader().valueMatches("Content-Disposition", "attachment;.*learning-agent-java-.*\\.db.*")
                .expectBody().returnResult().getResponseBody();

        assertTrue(snapshot != null && snapshot.length > 100);
        Path exportedPath = tempDir.resolve("exported.db");
        Files.write(exportedPath, snapshot);
        DataSource exported = dataSource(exportedPath);
        assertEquals("ok", JdbcClient.create(exported).sql("PRAGMA integrity_check").query(String.class).single());
        assertEquals(0, JdbcClient.create(exported).sql("SELECT COUNT(*) FROM pragma_foreign_key_check")
                .query(Integer.class).single());
        assertEquals(DatabaseSchema.MARKER, JdbcClient.create(exported).sql(
                "SELECT value FROM schema_metadata WHERE key = 'schema.marker'").query(String.class).single());
        assertTrue(JdbcClient.create(exported).sql(
                        "SELECT value FROM schema_metadata WHERE key = 'snapshot.created_at'").query(String.class).single()
                .endsWith("Z"));
        assertEquals(DatabaseSchema.SOURCE_APPLICATION_ID, JdbcClient.create(exported).sql(
                "SELECT value FROM schema_metadata WHERE key = 'source.application_id'").query(String.class).single());
        assertEquals("before", JdbcClient.create(exported).sql(
                "SELECT value FROM setting WHERE key = 'test.key'").query(String.class).single());
        assertEquals(1, count(exported, "learning_journey", "journey-1"));
        assertEquals(1, JdbcClient.create(exported).sql(
                "SELECT COUNT(*) FROM learning_journey_learn_unit WHERE journey_id = 'journey-1'").query(Integer.class).single());
        assertEquals(1, JdbcClient.create(exported).sql(
                "SELECT COUNT(*) FROM learner_profile WHERE journey_id = 'journey-1'").query(Integer.class).single());
        assertEquals(1, JdbcClient.create(exported).sql(
                "SELECT COUNT(*) FROM learn_unit WHERE code = 'python.basics'").query(Integer.class).single());
        assertEquals(1, count(exported, "learning_path_item", "path-1"));
        assertEquals(1, count(exported, "question", "question-1"));
        assertEquals(1, count(exported, "assessment", "assessment-1"));
        assertEquals(1, JdbcClient.create(exported).sql(
                "SELECT COUNT(*) FROM assessment_question WHERE assessment_id = 'assessment-1'").query(Integer.class).single());
        assertEquals(1, count(exported, "assessment_attempt", "attempt-1"));
        assertEquals(1, JdbcClient.create(exported).sql(
                "SELECT COUNT(*) FROM question_attempt WHERE assessment_attempt_id = 'attempt-1'").query(Integer.class).single());
        assertEquals(1, count(exported, "tutor_session", "tutor-1"));
        assertEquals(1, count(exported, "message", "message-1"));
        assertEquals(1, count(exported, "event", "event-1"));
        assertEquals(1, count(exported, "workflow_transition", "transition-1"));
        assertEquals(1, JdbcClient.create(exported).sql(
                "SELECT COUNT(*) FROM agent_state WHERE state_key = 'state'").query(Integer.class).single());

        activeJdbc.sql("UPDATE setting SET value = 'after' WHERE key = 'test.key'").update();
        assertEquals("before", JdbcClient.create(exported).sql(
                "SELECT value FROM setting WHERE key = 'test.key'").query(String.class).single());
    }

    @Test
    void importsTheWholeDatabaseAndLeavesAUsableBackup() throws Exception {
        Path sourcePath = tempDir.resolve("source.db");
        Path targetPath = tempDir.resolve("target.db");
        DataSource source = dataSource(sourcePath);
        DataSource target = dataSource(targetPath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        seedPortableDatabase(source, "source");
        seedPortableDatabase(target, "target");

        byte[] snapshot = new DatabaseExportService(source).export().bytes();
        WebTestClient client = WebTestClient.bindToController(new DatabaseController(new DatabaseExportService(target)))
                .build();

        client.post().uri("/api/database/import")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(snapshot)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.schemaVersion").isEqualTo(DatabaseSchema.VERSION)
                .jsonPath("$.restartRequired").isEqualTo(true);

        JdbcClient targetJdbc = JdbcClient.create(target);
        assertEquals("source", targetJdbc.sql("SELECT value FROM setting WHERE key = 'origin'")
                .query(String.class).single());
        assertEquals(1, count(target, "learning_journey", "journey-source"));
        assertEquals(1, targetJdbc.sql("SELECT COUNT(*) FROM agent_state WHERE state_key = 'state-source'")
                .query(Integer.class).single());
        assertEquals(0, count(target, "learning_journey", "journey-target"));
        assertEquals(0, targetJdbc.sql("SELECT COUNT(*) FROM agent_state WHERE state_key = 'state-target'")
                .query(Integer.class).single());

        Path backup = Files.list(tempDir.resolve("backups"))
                .filter(path -> path.getFileName().toString().contains("pre-import"))
                .findFirst().orElseThrow();
        DataSource backupDataSource = dataSource(backup);
        assertEquals("ok", JdbcClient.create(backupDataSource).sql("PRAGMA integrity_check")
                .query(String.class).single());
        assertEquals("target", JdbcClient.create(backupDataSource).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());
        assertEquals(0, Files.list(tempDir).filter(path -> path.getFileName().toString().contains("import-")).count());
    }

    @Test
    void rejectsInvalidImportWithoutChangingTheCurrentDatabase() throws Exception {
        Path targetPath = tempDir.resolve("target.db");
        DataSource target = dataSource(targetPath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        JdbcClient.create(target).sql("INSERT INTO setting (key, value, updated_at) VALUES ('origin', 'target', 'now')").update();
        WebTestClient client = WebTestClient.bindToController(new DatabaseController(new DatabaseExportService(target)))
                .build();

        client.post().uri("/api/database/import")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue("not sqlite".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error").isEqualTo("database_import_invalid_file");

        assertEquals("target", JdbcClient.create(target).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());
        assertEquals(0, Files.list(tempDir).filter(path -> path.getFileName().toString().contains("pre-import")).count());
    }

    @Test
    void warnsBeforeReplacingNewerDatabaseAndRequiresExplicitConfirmation() throws Exception {
        Path sourcePath = tempDir.resolve("source.db");
        Path targetPath = tempDir.resolve("target.db");
        DataSource source = dataSource(sourcePath);
        DataSource target = dataSource(targetPath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        seedPortableDatabase(source, "source");
        seedPortableDatabase(target, "target");
        byte[] snapshot = new DatabaseExportService(source).export().bytes();
        Files.setLastModifiedTime(targetPath, FileTime.from(Instant.now().plusSeconds(120)));

        WebTestClient client = WebTestClient.bindToController(new DatabaseController(new DatabaseExportService(target)))
                .build();
        client.post().uri("/api/database/import")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(snapshot)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("database_import_stale")
                .jsonPath("$.confirmationRequired").isEqualTo(true)
                .jsonPath("$.snapshotCreatedAt").isNotEmpty()
                .jsonPath("$.currentDatabaseAt").isNotEmpty();
        assertEquals("target", JdbcClient.create(target).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());

        client.post().uri("/api/database/import?confirm=true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(snapshot)
                .exchange()
                .expectStatus().isOk();
        assertEquals("source", JdbcClient.create(target).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());
    }

    @Test
    void rejectsImportWhileAnotherTransferOrTutorRunOwnsTheMaintenanceWindow() throws Exception {
        Path targetPath = tempDir.resolve("target.db");
        DataSource target = dataSource(targetPath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        DatabaseTransferCoordinator coordinator = new DatabaseTransferCoordinator();
        DatabaseExportService service = new DatabaseExportService(target, coordinator);
        WebTestClient client = WebTestClient.bindToController(new DatabaseController(service)).build();

        try (DatabaseTransferCoordinator.Lease transfer = coordinator.beginExport()) {
            client.post().uri("/api/database/import")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(new byte[]{1})
                    .exchange()
                    .expectStatus().isEqualTo(409)
                    .expectBody().jsonPath("$.error").isEqualTo("database_transfer_busy");
        }
        try (DatabaseTransferCoordinator.Lease agent = coordinator.beginAgentRun()) {
            client.post().uri("/api/database/import")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(new byte[]{1})
                    .exchange()
                    .expectStatus().isEqualTo(409)
                    .expectBody().jsonPath("$.error").isEqualTo("database_agent_busy");
        }
        assertEquals(0, Files.list(tempDir)
                .filter(path -> path.getFileName().toString().contains("learning-agent-java-import-")).count());
    }

    @Test
    void reportsBackupFailureWithoutChangingTheCurrentDatabaseOrLeavingUploadFiles() throws Exception {
        Path targetPath = tempDir.resolve("target.db");
        Path sourcePath = tempDir.resolve("source.db");
        DataSource target = dataSource(targetPath);
        DataSource source = dataSource(sourcePath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        seedPortableDatabase(target, "target");
        seedPortableDatabase(source, "source");
        byte[] snapshot = new DatabaseExportService(source).export().bytes();
        Files.writeString(tempDir.resolve("backups"), "not a directory");

        WebTestClient client = WebTestClient.bindToController(new DatabaseController(new DatabaseExportService(target)))
                .build();
        client.post().uri("/api/database/import?confirm=true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(snapshot)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.error").isEqualTo("database_import_backup_failed");
        assertEquals("target", JdbcClient.create(target).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());
        assertEquals(0, Files.list(tempDir)
                .filter(path -> path.getFileName().toString().contains("learning-agent-java-import-")).count());
    }

    @Test
    void restoresThePreImportBackupWhenReplacementValidationFails() throws Exception {
        Path targetPath = tempDir.resolve("target.db");
        Path sourcePath = tempDir.resolve("source.db");
        DataSource target = dataSource(targetPath);
        DataSource source = dataSource(sourcePath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        seedPortableDatabase(target, "target");
        seedPortableDatabase(source, "source");
        byte[] snapshot = new DatabaseExportService(source).export().bytes();
        DatabaseExportService failing = new DatabaseExportService(target) {
            @Override
            void replace(Path uploaded, Path active) throws java.io.IOException {
                super.replace(uploaded, active);
                Files.writeString(active, "corrupted after replacement");
            }
        };

        WebTestClient client = WebTestClient.bindToController(new DatabaseController(failing)).build();
        client.post().uri("/api/database/import?confirm=true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(snapshot)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.error").isEqualTo("database_import_replace_failed");
        assertEquals("target", JdbcClient.create(target).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());
        assertEquals(0, Files.list(tempDir)
                .filter(path -> path.getFileName().toString().contains("learning-agent-java-import-")).count());
    }

    @Test
    void rejectsUnknownMarkerAndSchemaVersionWithStableCodes() throws Exception {
        Path targetPath = tempDir.resolve("target.db");
        Path sourcePath = tempDir.resolve("source.db");
        Path snapshotPath = tempDir.resolve("snapshot.db");
        DataSource target = dataSource(targetPath);
        DataSource source = dataSource(sourcePath);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(target);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        seedPortableDatabase(target, "target");
        seedPortableDatabase(source, "source");
        Files.write(snapshotPath, new DatabaseExportService(source).export().bytes());
        JdbcClient snapshotJdbc = JdbcClient.create(dataSource(snapshotPath));
        WebTestClient client = WebTestClient.bindToController(new DatabaseController(new DatabaseExportService(target)))
                .build();

        snapshotJdbc.sql("UPDATE schema_metadata SET value = 'unknown' WHERE key = 'schema.marker'").update();
        client.post().uri("/api/database/import?confirm=true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(Files.readAllBytes(snapshotPath))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error").isEqualTo("database_import_schema_unknown");

        snapshotJdbc.sql("UPDATE schema_metadata SET value = ? WHERE key = 'schema.marker'")
                .param(DatabaseSchema.MARKER).update();
        snapshotJdbc.sql("UPDATE schema_metadata SET value = '999' WHERE key = 'schema.version'").update();
        client.post().uri("/api/database/import?confirm=true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(Files.readAllBytes(snapshotPath))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error").isEqualTo("database_import_schema_version");
        assertEquals("target", JdbcClient.create(target).sql(
                "SELECT value FROM setting WHERE key = 'origin'").query(String.class).single());
    }

    private DataSource dataSource(Path path) {
        return new SimpleDriverDataSource(new JDBC(), "jdbc:sqlite:" + path);
    }

    private int count(DataSource dataSource, String table, String id) {
        return JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM " + table + " WHERE id = ?")
                .param(id).query(Integer.class).single();
    }

    private void seedPortableDatabase(DataSource dataSource, String origin) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO learning_language VALUES (?, 'python', 'Python', 'Python', 1)")
                .param("lang-" + origin).update();
        jdbc.sql("INSERT INTO learning_journey VALUES (?, 'user-1', 'python', 'Learn Python', 'ACTIVE', 'now', 'now')")
                .param("journey-" + origin).update();
        jdbc.sql("INSERT INTO chapter VALUES (?, ?, ?, 'Basics', 'Basics', 1, '[]')")
                .params("chapter-" + origin, "journey-" + origin, "python.basics-chapter-" + origin).update();
        jdbc.sql("INSERT INTO learn_unit VALUES (?, 'python', ?, ?, 'Basics', 'Basics', 1, '[]', 80, NULL, 1, '[]', 'Intro', '[]', '[]', 1, '', 0, '', '[]', '')")
                .params("unit-" + origin, "python.basics-" + origin, "python.basics-chapter-" + origin).update();
        jdbc.sql("INSERT INTO setting (key, value, updated_at) VALUES ('origin', ?, 'now')").param(origin).update();
        jdbc.sql("INSERT INTO agent_state VALUES ('user-1', ?, ?, 'test', '{}', 1, 'now')")
                .params("session-" + origin, "state-" + origin).update();
    }
}
