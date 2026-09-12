package com.example.agent.config;

import com.example.agent.persistence.SqliteRepository;
import com.example.agent.api.ModelConfigurationController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.sqlite.JDBC;

import java.nio.file.Path;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProviderConfigurationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesSettingsAndPreservesTheExistingKeyWhenTheFormLeavesItBlank() {
        JdbcClient jdbc = jdbc();
        SqliteRepository repository = new SqliteRepository(jdbc);
        ModelProviderConfigurationService service = service(repository, new MockEnvironment());

        service.save(new ModelProviderConfiguration(
                ModelProviderConfiguration.OPENAI_COMPATIBLE,
                "https://example.com/v1/",
                "secret-key",
                "example-model"));
        service.save(new ModelProviderConfiguration(
                ModelProviderConfiguration.OPENAI_COMPATIBLE,
                "https://example.com/v1",
                null,
                "example-model-2"));

        assertEquals("https://example.com/v1", service.current().baseUrl());
        assertEquals("example-model-2", service.current().model());
        assertEquals("secret-key", service.current().apiKey());
        assertEquals("app", service.source());

        ModelConfigurationController.ModelConfigurationResponse response =
                new ModelConfigurationController(service).get();
        assertEquals("••••-key", response.apiKeyMasked());
        assertEquals(true, response.apiKeyConfigured());
    }

    @Test
    void explicitEnvironmentValuesOverrideSavedSettings() {
        JdbcClient jdbc = jdbc();
        SqliteRepository repository = new SqliteRepository(jdbc);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_BASE_URL", "https://environment.example/v1")
                .withProperty("OPENAI_API_KEY", "environment-key")
                .withProperty("OPENAI_MODEL", "environment-model");
        ModelProviderConfigurationService service = service(repository, environment);

        service.save(new ModelProviderConfiguration(
                ModelProviderConfiguration.OPENAI_COMPATIBLE,
                "https://saved.example/v1",
                "saved-key",
                "saved-model"));

        assertEquals("https://environment.example/v1", service.current().baseUrl());
        assertEquals("environment-key", service.current().apiKey());
        assertEquals("environment-model", service.current().model());
        assertEquals("environment", service.source());
    }

    @Test
    void rejectsNonHttpBaseUrls() {
        ModelProviderConfigurationService service = service(new SqliteRepository(jdbc()), new MockEnvironment());

        assertThrows(IllegalArgumentException.class, () -> service.save(new ModelProviderConfiguration(
                ModelProviderConfiguration.OPENAI_COMPATIBLE, "file:///tmp/model", "key", "model")));
    }

    private ModelProviderConfigurationService service(SqliteRepository repository, MockEnvironment environment) {
        return new ModelProviderConfigurationService(
                repository, environment, "https://api.openai.com", "", "gpt-4.1-mini");
    }

    private JdbcClient jdbc() {
        DataSource dataSource = new SimpleDriverDataSource(
                new JDBC(), "jdbc:sqlite:" + tempDir.resolve(Path.of("model-settings.db")));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        CREATE TABLE setting (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """).update();
        return jdbc;
    }
}
