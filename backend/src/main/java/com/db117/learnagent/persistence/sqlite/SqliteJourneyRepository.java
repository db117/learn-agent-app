package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.JourneyStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** SQLite Journey 适配器；当前选择和路径关联与目标描述在同一 Domain 数据库中保存。 */
@ApplicationScoped
public class SqliteJourneyRepository implements JourneyRepository {
    private final DataSource dataSource;

    public SqliteJourneyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Journey save(Journey journey) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            connection.setAutoCommit(false);
            try {
                if (journey.current()) {
                    clearCurrent(connection, journey.learnerId());
                }
                var saved = journey.id() == null
                        ? insert(connection, journey)
                        : update(connection, journey);
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save journey", error);
        }
    }

    @Override
    public Optional<Journey> findById(long id) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return read(connection, "WHERE id = ?", statement -> statement.setLong(1, id));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find journey", error);
        }
    }

    @Override
    public List<Journey> findByLearnerId(long learnerId) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return readAll(connection, "WHERE learner_id = ? ORDER BY created_at DESC, id DESC",
                    statement -> statement.setLong(1, learnerId));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to list journeys", error);
        }
    }

    @Override
    public Optional<Journey> findCurrentByLearnerId(long learnerId) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return read(connection, "WHERE learner_id = ? AND is_current = 1",
                    statement -> statement.setLong(1, learnerId));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find current journey", error);
        }
    }

    @Override
    public Journey selectCurrent(long journeyId, long learnerId) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            connection.setAutoCommit(false);
            try {
                var target = read(connection, "WHERE id = ? AND learner_id = ?",
                        statement -> {
                            statement.setLong(1, journeyId);
                            statement.setLong(2, learnerId);
                        }).orElseThrow(() -> new IllegalStateException("Journey does not belong to learner"));
                if (target.status() != JourneyStatus.ACTIVE) {
                    throw new IllegalStateException("Only active journey can be selected");
                }
                clearCurrent(connection, learnerId);
                try (var statement = connection.prepareStatement(
                        "UPDATE journey SET is_current = 1 WHERE id = ? AND learner_id = ?")) {
                    statement.setLong(1, journeyId);
                    statement.setLong(2, learnerId);
                    SqliteSupport.requireUpdated(statement.executeUpdate(), "journey", journeyId);
                }
                connection.commit();
                return read(connection, "WHERE id = ?",
                        statement -> statement.setLong(1, journeyId)).orElseThrow();
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to select current journey", error);
        }
    }

    @Override
    public Journey attachLearningJourney(long journeyId, long learningJourneyId, long learnerId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE journey SET learning_journey_id = ? "
                             + "WHERE id = ? AND learner_id = ? "
                             + "AND EXISTS (SELECT 1 FROM learning_journey "
                             + "WHERE learning_journey.id = ? AND learning_journey.learner_id = ?)")) {
            statement.setLong(1, learningJourneyId);
            statement.setLong(2, journeyId);
            statement.setLong(3, learnerId);
            statement.setLong(4, learningJourneyId);
            statement.setLong(5, learnerId);
            SqliteSupport.requireUpdated(statement.executeUpdate(), "journey", journeyId);
            return read(connection, "WHERE id = ?",
                    lookup -> lookup.setLong(1, journeyId)).orElseThrow();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to attach learning journey", error);
        }
    }

    private Journey insert(Connection connection, Journey journey) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO journey(learner_id, goal_description, status, created_at, archived_at, "
                        + "learning_journey_id, is_current) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, journey.learnerId());
            statement.setString(2, journey.goalDescription());
            statement.setString(3, journey.status().name());
            statement.setString(4, journey.createdAt().toString());
            statement.setString(5, SqliteSupport.instant(journey.archivedAt()));
            if (journey.learningJourneyId() == null) {
                statement.setObject(6, null);
            } else {
                statement.setLong(6, journey.learningJourneyId());
            }
            statement.setInt(7, SqliteSupport.bool(journey.current()));
            statement.executeUpdate();
            return withId(journey, SqliteSupport.generatedId(connection, statement));
        }
    }

    private Journey update(Connection connection, Journey journey) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE journey SET goal_description = ?, status = ?, archived_at = ?, "
                        + "learning_journey_id = ?, is_current = ? WHERE id = ? AND learner_id = ?")) {
            statement.setString(1, journey.goalDescription());
            statement.setString(2, journey.status().name());
            statement.setString(3, SqliteSupport.instant(journey.archivedAt()));
            if (journey.learningJourneyId() == null) {
                statement.setObject(4, null);
            } else {
                statement.setLong(4, journey.learningJourneyId());
            }
            statement.setInt(5, SqliteSupport.bool(journey.current()));
            statement.setLong(6, journey.id());
            statement.setLong(7, journey.learnerId());
            SqliteSupport.requireUpdated(statement.executeUpdate(), "journey", journey.id());
            return journey;
        }
    }

    private void clearCurrent(Connection connection, long learnerId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE journey SET is_current = 0 WHERE learner_id = ? AND is_current = 1")) {
            statement.setLong(1, learnerId);
            statement.executeUpdate();
        }
    }

    private Optional<Journey> read(Connection connection, String predicate, SqlBinder binder) throws SQLException {
        try (var statement = connection.prepareStatement(selectSql() + predicate)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private List<Journey> readAll(Connection connection, String predicate, SqlBinder binder) throws SQLException {
        var journeys = new ArrayList<Journey>();
        try (var statement = connection.prepareStatement(selectSql() + predicate)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    journeys.add(read(result));
                }
            }
        }
        return List.copyOf(journeys);
    }

    private String selectSql() {
        return "SELECT id, learner_id, goal_description, status, created_at, archived_at, "
                + "learning_journey_id, is_current FROM journey ";
    }

    private Journey read(ResultSet result) throws SQLException {
        long learningJourneyId = result.getLong("learning_journey_id");
        boolean learningJourneyIdWasNull = result.wasNull();
        return new Journey(
                result.getLong("id"),
                result.getLong("learner_id"),
                result.getString("goal_description"),
                JourneyStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("created_at")),
                SqliteSupport.parseInstant(result, "archived_at"),
                learningJourneyIdWasNull ? null : learningJourneyId,
                SqliteSupport.bool(result, "is_current"));
    }

    private Journey withId(Journey journey, long id) {
        return new Journey(
                id,
                journey.learnerId(),
                journey.goalDescription(),
                journey.status(),
                journey.createdAt(),
                journey.archivedAt(),
                journey.learningJourneyId(),
                journey.current());
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }
}
