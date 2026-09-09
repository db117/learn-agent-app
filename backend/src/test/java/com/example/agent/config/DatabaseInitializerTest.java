package com.example.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesAnEmptyDatabaseWithTheReplacementSchema() {
        DataSource dataSource = dataSource("new.db");

        assertDoesNotThrow(() -> new DatabaseInitializer(dataSource).initialize());
        assertEqualsMarker(dataSource);
    }

    @Test
    void refusesAnExistingDatabaseWithoutTheReplacementMarker() {
        DataSource dataSource = dataSource("legacy.db");
        JdbcClient.create(dataSource).sql("CREATE TABLE session (id TEXT PRIMARY KEY)").update();

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> new DatabaseInitializer(dataSource).initialize());

        assertTrue(error.getMessage().contains("refusing to migrate or overwrite"));
    }

    private DataSource dataSource(String name) {
        return new SimpleDriverDataSource(new JDBC(), "jdbc:sqlite:" + tempDir.resolve(name));
    }

    private void assertEqualsMarker(DataSource dataSource) {
        assertEquals(DatabaseInitializer.SCHEMA_VERSION, JdbcClient.create(dataSource).sql(
                "SELECT value FROM schema_metadata WHERE key = 'schema.version'")
                .query(String.class).single());
    }
}
