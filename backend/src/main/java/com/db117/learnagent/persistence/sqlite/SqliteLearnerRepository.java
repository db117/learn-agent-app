package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/** SQLite 对 Learner 身份的持久化适配器；不保存 Journey 进度。 */
@ApplicationScoped
public class SqliteLearnerRepository implements LearnerRepository {
    private final DataSource dataSource;

    public SqliteLearnerRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Learner save(Learner learner) {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            if (learner.id() == null) {
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO learner(display_name, background_summary, created_at) VALUES (?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, learner.displayName());
                    statement.setString(2, learner.backgroundSummary());
                    statement.setString(3, learner.createdAt().toString());
                    statement.executeUpdate();
                    return learner.withId(SqliteSupport.generatedId(connection, statement));
                }
            }
            // ID 由 SQLite 生成；Domain 对象在保存前可以保持未持久化状态。
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "UPDATE learner SET display_name = ?, background_summary = ?, created_at = ? WHERE id = ?")) {
                statement.setString(1, learner.displayName());
                statement.setString(2, learner.backgroundSummary());
                statement.setString(3, learner.createdAt().toString());
                statement.setLong(4, learner.id());
                requireUpdated(statement.executeUpdate(), "learner", learner.id());
                return learner;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save learner", error);
        }
    }

    @Override
    public Optional<Learner> findById(long id) {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, display_name, background_summary, created_at FROM learner WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find learner", error);
        }
    }

    @Override
    public Optional<Learner> findCurrent() {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, display_name, background_summary, created_at "
                             + "FROM learner ORDER BY id LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(read(result)) : Optional.empty();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find current learner", error);
        }
    }

    private Learner read(ResultSet result) throws SQLException {
        return new Learner(
                result.getLong("id"),
                result.getString("display_name"),
                result.getString("background_summary"),
                Instant.parse(result.getString("created_at")));
    }

    private void requireUpdated(int count, String entity, long id) {
        if (count != 1) {
            throw new IllegalStateException("Cannot update missing " + entity + ": " + id);
        }
    }
}
