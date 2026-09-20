package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.learning.domain.LearningJourneyStatus;
import com.db117.learnagent.learning.domain.LearningPathItem;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * LearningJourney 聚合的 SQLite 适配器。
 *
 * <p>内容快照和路径状态在同一事务中保存，避免只更新到聚合的一半。</p>
 */
@ApplicationScoped
public class SqliteLearningJourneyRepository implements LearningJourneyRepository {
    private final DataSource dataSource;

    public SqliteLearningJourneyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public LearningJourney save(LearningJourney journey) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            connection.setAutoCommit(false);
            try {
                var saved = journey.id() == null ? insertNew(connection, journey) : updateExisting(connection, journey);
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save learning journey", error);
        }
    }

    @Override
    public void delete(long id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("DELETE FROM learning_journey WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to delete learning journey", error);
        }
    }

    @Override
    public Optional<LearningJourney> findById(long id) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return readJourney(connection, "WHERE id = ?", statement -> statement.setLong(1, id));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find learning journey", error);
        }
    }

    @Override
    public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
        try (var connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return readJourney(connection,
                    "WHERE learner_id = ? AND language_pack_id = ? AND status = 'ACTIVE'",
                    statement -> {
                        statement.setLong(1, learnerId);
                        statement.setString(2, languagePackId);
                    });
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find active learning journey", error);
        }
    }

    private LearningJourney insertNew(Connection connection, LearningJourney journey) throws SQLException {
        // 先保存根，再按外键依赖顺序保存内容和路径。
        long journeyId;
        try (var statement = connection.prepareStatement(
                "INSERT INTO learning_journey(learner_id, language_pack_id, title, status, created_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, journey.learnerId());
            statement.setString(2, journey.languagePackId());
            statement.setString(3, journey.title());
            statement.setString(4, journey.status().name());
            statement.setString(5, journey.createdAt().toString());
            statement.setString(6, SqliteSupport.instant(journey.completedAt()));
            statement.executeUpdate();
            journeyId = SqliteSupport.generatedId(connection, statement);
        }

        var persistedChapters = insertChapters(connection, journeyId, journey.chapters());
        var chapterIds = persistedChapters.stream().collect(java.util.stream.Collectors.toMap(
                Chapter::code, Chapter::id));
        var persistedUnits = insertLearnUnits(connection, journeyId, chapterIds, journey.learnUnits());
        var unitIds = persistedUnits.stream().collect(java.util.stream.Collectors.toMap(
                LearnUnit::code, LearnUnit::id));
        var persistedItems = insertPathItems(connection, journeyId, unitIds, journey.pathItems());
        return LearningJourney.reconstitute(
                journeyId,
                journey.learnerId(),
                journey.languagePackId(),
                journey.title(),
                journey.status(),
                journey.createdAt(),
                journey.completedAt(),
                persistedChapters,
                persistedUnits,
                persistedItems);
    }

    private LearningJourney updateExisting(Connection connection, LearningJourney journey) throws SQLException {
        // 已有内容快照只读；更新阶段只写聚合状态和路径进度。
        long journeyId = journey.id();
        try (var statement = connection.prepareStatement(
                "UPDATE learning_journey SET title = ?, status = ?, completed_at = ? "
                        + "WHERE id = ? AND learner_id = ? AND language_pack_id = ?")) {
            statement.setString(1, journey.title());
            statement.setString(2, journey.status().name());
            statement.setString(3, SqliteSupport.instant(journey.completedAt()));
            statement.setLong(4, journeyId);
            statement.setLong(5, journey.learnerId());
            statement.setString(6, journey.languagePackId());
            SqliteSupport.requireUpdated(statement.executeUpdate(), "learning journey", journeyId);
        }
        requirePersistedContent(journey);
        var unitIds = journey.learnUnits().stream().collect(java.util.stream.Collectors.toMap(
                LearnUnit::code, LearnUnit::id));
        var persistedItems = updatePathItems(connection, journeyId, unitIds, journey.pathItems());
        return LearningJourney.reconstitute(
                journeyId,
                journey.learnerId(),
                journey.languagePackId(),
                journey.title(),
                journey.status(),
                journey.createdAt(),
                journey.completedAt(),
                journey.chapters(),
                journey.learnUnits(),
                persistedItems);
    }

    private void requirePersistedContent(LearningJourney journey) {
        if (journey.chapters().stream().anyMatch(chapter -> chapter.id() == null)
                || journey.learnUnits().stream().anyMatch(unit -> unit.id() == null)) {
            throw new IllegalStateException("persisted journey content cannot be replaced or added");
        }
    }

    private List<Chapter> insertChapters(Connection connection, long journeyId, List<Chapter> chapters)
            throws SQLException {
        var persisted = new ArrayList<Chapter>();
        try (var statement = connection.prepareStatement(
                "INSERT INTO chapter(journey_id, code, title, sequence) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (Chapter chapter : chapters) {
                statement.setLong(1, journeyId);
                statement.setString(2, chapter.code());
                statement.setString(3, chapter.title());
                statement.setInt(4, chapter.sequence());
                statement.executeUpdate();
                persisted.add(chapter.withId(SqliteSupport.generatedId(connection, statement)));
            }
        }
        return List.copyOf(persisted);
    }

    private List<LearnUnit> insertLearnUnits(
            Connection connection, long journeyId, Map<String, Long> chapterIds, List<LearnUnit> units)
            throws SQLException {
        var persisted = new ArrayList<LearnUnit>();
        try (var statement = connection.prepareStatement(
                "INSERT INTO learn_unit(journey_id, chapter_id, code, title, objective, content, sequence, prerequisite_codes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (LearnUnit unit : units) {
                statement.setLong(1, journeyId);
                statement.setLong(2, chapterIds.get(unit.chapterCode()));
                statement.setString(3, unit.code());
                statement.setString(4, unit.title());
                statement.setString(5, unit.objective());
                statement.setString(6, unit.content());
                statement.setInt(7, unit.sequence());
                statement.setString(8, SqliteJson.write(unit.prerequisiteCodes()));
                statement.executeUpdate();
                persisted.add(unit.withId(SqliteSupport.generatedId(connection, statement)));
            }
        }
        return List.copyOf(persisted);
    }

    private List<LearningPathItem> insertPathItems(
            Connection connection, long journeyId, Map<String, Long> unitIds, List<LearningPathItem> items)
            throws SQLException {
        var persisted = new ArrayList<LearningPathItem>();
        try (var statement = connection.prepareStatement(pathItemInsertSql(), Statement.RETURN_GENERATED_KEYS)) {
            for (LearningPathItem item : items) {
                bindPathItem(statement, journeyId, unitIds.get(item.learnUnitCode()), item);
                statement.executeUpdate();
                persisted.add(item.withId(SqliteSupport.generatedId(connection, statement)));
            }
        }
        return List.copyOf(persisted);
    }

    private List<LearningPathItem> updatePathItems(
            Connection connection, long journeyId, Map<String, Long> unitIds, List<LearningPathItem> items)
            throws SQLException {
        var persisted = new ArrayList<LearningPathItem>();
        try (var update = connection.prepareStatement(pathItemUpdateSql());
             var insert = connection.prepareStatement(pathItemInsertSql(), Statement.RETURN_GENERATED_KEYS)) {
            for (LearningPathItem item : items) {
                if (item.id() == null) {
                    bindPathItem(insert, journeyId, unitIds.get(item.learnUnitCode()), item);
                    insert.executeUpdate();
                    persisted.add(item.withId(SqliteSupport.generatedId(connection, insert)));
                } else {
                    bindPathItemUpdate(update, item);
                    update.executeUpdate();
                    persisted.add(item);
                }
            }
        }
        return List.copyOf(persisted);
    }

    private Optional<LearningJourney> readJourney(
            Connection connection, String predicate, SqlBinder binder) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id, learner_id, language_pack_id, title, status, created_at, completed_at "
                        + "FROM learning_journey " + predicate)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readJourney(connection, result));
            }
        }
    }

    private LearningJourney readJourney(Connection connection, ResultSet result) throws SQLException {
        long journeyId = result.getLong("id");
        var chapters = readChapters(connection, journeyId);
        var units = readLearnUnits(connection, journeyId);
        var items = readPathItems(connection, journeyId);
        return LearningJourney.reconstitute(
                journeyId,
                result.getLong("learner_id"),
                result.getString("language_pack_id"),
                result.getString("title"),
                LearningJourneyStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("created_at")),
                SqliteSupport.parseInstant(result, "completed_at"),
                chapters,
                units,
                items);
    }

    private List<Chapter> readChapters(Connection connection, long journeyId) throws SQLException {
        var chapters = new ArrayList<Chapter>();
        try (var statement = connection.prepareStatement(
                "SELECT id, code, title, sequence FROM chapter WHERE journey_id = ? ORDER BY sequence, code")) {
            statement.setLong(1, journeyId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    chapters.add(new Chapter(
                            result.getLong("id"),
                            result.getString("code"),
                            result.getString("title"),
                            result.getInt("sequence")));
                }
            }
        }
        return List.copyOf(chapters);
    }

    private List<LearnUnit> readLearnUnits(Connection connection, long journeyId) throws SQLException {
        var units = new ArrayList<LearnUnit>();
        try (var statement = connection.prepareStatement(
                "SELECT id, code, title, objective, content, sequence, "
                        + "(SELECT code FROM chapter WHERE chapter.id = learn_unit.chapter_id) AS chapter_code, "
                        + "prerequisite_codes FROM learn_unit WHERE journey_id = ? ORDER BY id")) {
            statement.setLong(1, journeyId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    units.add(new LearnUnit(
                            result.getLong("id"),
                            result.getString("code"),
                            result.getString("title"),
                            result.getString("objective"),
                            result.getString("content"),
                            result.getInt("sequence"),
                            result.getString("chapter_code"),
                            SqliteJson.stringSet(result.getString("prerequisite_codes"))));
                }
            }
        }
        return List.copyOf(units);
    }

    private List<LearningPathItem> readPathItems(Connection connection, long journeyId) throws SQLException {
        var items = new ArrayList<LearningPathItem>();
        try (var statement = connection.prepareStatement(
                "SELECT id, learn_unit_code, sequence, status, practice_verified, pass_reason, started_at, "
                        + "completed_at, updated_at "
                        + "FROM learning_path_item WHERE journey_id = ? ORDER BY id")) {
            statement.setLong(1, journeyId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new LearningPathItem(
                            result.getLong("id"),
                            result.getString("learn_unit_code"),
                            result.getInt("sequence"),
                            LearningPathItemStatus.valueOf(result.getString("status")),
                            SqliteSupport.bool(result, "practice_verified"),
                            result.getString("pass_reason"),
                            SqliteSupport.parseInstant(result, "started_at"),
                            SqliteSupport.parseInstant(result, "completed_at"),
                            Instant.parse(result.getString("updated_at"))));
                }
            }
        }
        return List.copyOf(items);
    }

    private String pathItemInsertSql() {
        return "INSERT INTO learning_path_item(journey_id, learn_unit_id, learn_unit_code, sequence, status, "
                + "practice_verified, pass_reason, started_at, completed_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private String pathItemUpdateSql() {
        return "UPDATE learning_path_item SET sequence = ?, status = ?, practice_verified = ?, pass_reason = ?, "
                + "started_at = ?, completed_at = ?, updated_at = ? WHERE id = ?";
    }

    private void bindPathItem(PreparedStatement statement, long journeyId, long learnUnitId, LearningPathItem item)
            throws SQLException {
        statement.setLong(1, journeyId);
        statement.setLong(2, learnUnitId);
        statement.setString(3, item.learnUnitCode());
        statement.setInt(4, item.sequence());
        statement.setString(5, item.status().name());
        statement.setInt(6, SqliteSupport.bool(item.practiceVerified()));
        statement.setString(7, item.passReason());
        statement.setString(8, SqliteSupport.instant(item.startedAt()));
        statement.setString(9, SqliteSupport.instant(item.completedAt()));
        statement.setString(10, item.updatedAt().toString());
    }

    private void bindPathItemUpdate(PreparedStatement statement, LearningPathItem item) throws SQLException {
        statement.setInt(1, item.sequence());
        statement.setString(2, item.status().name());
        statement.setInt(3, SqliteSupport.bool(item.practiceVerified()));
        statement.setString(4, item.passReason());
        statement.setString(5, SqliteSupport.instant(item.startedAt()));
        statement.setString(6, SqliteSupport.instant(item.completedAt()));
        statement.setString(7, item.updatedAt().toString());
        statement.setLong(8, item.id());
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }
}
