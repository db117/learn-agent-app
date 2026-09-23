package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.config.ModelConfiguration;
import com.db117.learnagent.config.OpenAIProtocol;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/** SQLite 应用配置适配器；模型配置独立于 Learning Domain 表。 */
@ApplicationScoped
public class SqliteModelConfigurationRepository {
    private final DataSource dataSource;

    public SqliteModelConfigurationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<ModelConfiguration> find() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT model_name, base_url, api_key, protocol FROM model_configuration WHERE id = 1");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return Optional.empty();
            }
            return Optional.of(new ModelConfiguration(
                    result.getString("model_name"),
                    result.getString("base_url"),
                    result.getString("api_key"),
                    OpenAIProtocol.valueOf(result.getString("protocol"))));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read model configuration", error);
        }
    }

    public void save(ModelConfiguration configuration) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO model_configuration(id, model_name, base_url, api_key, protocol)
                    VALUES (1, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        model_name = excluded.model_name,
                        base_url = excluded.base_url,
                        api_key = excluded.api_key,
                        protocol = excluded.protocol
                    """)) {
                statement.setString(1, configuration.modelName());
                statement.setString(2, configuration.baseUrl());
                statement.setString(3, configuration.apiKey());
                statement.setString(4, configuration.protocol().name());
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save model configuration", error);
        }
    }
}
