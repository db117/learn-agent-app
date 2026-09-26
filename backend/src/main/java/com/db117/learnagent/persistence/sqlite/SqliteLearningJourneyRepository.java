package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.learning.domain.*;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        try (Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            connection.setAutoCommit(false);
            try {
                LearningJourney saved = journey.id() == null ? insertNew(connection, journey) : updateExisting(connection, journey);
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM learning_journey WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to delete learning journey", error);
        }
    }

    @Override
    public Optional<LearningJourney> findById(long id) {
        try (Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return readJourney(connection, "WHERE id = ?", statement -> statement.setLong(1, id));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find learning journey", error);
        }
    }

    @Override
    public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
        try (Connection connection = dataSource.getConnection()) {
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
        try (PreparedStatement statement = connection.prepareStatement(
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

        List<Chapter> persistedChapters = insertChapters(connection, journeyId, journey.chapters());
        Map<String, Long> chapterIds = persistedChapters.stream().collect(java.util.stream.Collectors.toMap(
                Chapter::code, Chapter::id));
        List<LearnUnit> persistedUnits = insertLearnUnits(connection, journeyId, chapterIds, journey.learnUnits());
        Map<String, Long> unitIds = persistedUnits.stream().collect(java.util.stream.Collectors.toMap(
                LearnUnit::code, LearnUnit::id));
        List<LearningPathItem> persistedItems = insertPathItems(connection, journeyId, unitIds, journey.pathItems());
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
        // 已有路径结构保持不变；进入 LearnUnit 后允许补写一次教学内容快照。
        long journeyId = journey.id();
        try (PreparedStatement statement = connection.prepareStatement(
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
        List<Chapter> persistedChapters = persistChapters(connection, journeyId, journey.chapters());
        Map<String, Long> chapterIds = persistedChapters.stream().collect(java.util.stream.Collectors.toMap(
                Chapter::code, Chapter::id));
        List<LearnUnit> persistedUnits = persistLearnUnits(
                connection, journeyId, chapterIds, journey.learnUnits());
        deleteUnusedChapters(connection, journeyId);
        Map<String, Long> unitIds = persistedUnits.stream().collect(java.util.stream.Collectors.toMap(
                LearnUnit::code, LearnUnit::id));
        // 先释放唯一 CURRENT 索引，再按聚合的新路线顺序写入，避免重排中途触发约束。
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE learning_path_item SET status = 'PENDING' WHERE journey_id = ? AND status = 'CURRENT'")) {
            statement.setLong(1, journeyId);
            statement.executeUpdate();
        }
        List<LearningPathItem> persistedItems = updatePathItems(connection, journeyId, unitIds, journey.pathItems());
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

    private void deleteUnusedChapters(Connection connection, long journeyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM chapter WHERE journey_id = ? "
                        + "AND id NOT IN (SELECT chapter_id FROM learn_unit WHERE journey_id = ?)")) {
            statement.setLong(1, journeyId);
            statement.setLong(2, journeyId);
            statement.executeUpdate();
        }
    }

    private List<Chapter> persistChapters(Connection connection, long journeyId, List<Chapter> chapters)
            throws SQLException {
        ArrayList<Chapter> persisted = new ArrayList<Chapter>();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO chapter(journey_id, code, title, sequence) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE chapter SET title = ?, sequence = ? WHERE id = ? AND journey_id = ?")) {
            for (Chapter chapter : chapters) {
                if (chapter.id() == null) {
                    insert.setLong(1, journeyId);
                    insert.setString(2, chapter.code());
                    insert.setString(3, chapter.title());
                    insert.setInt(4, chapter.sequence());
                    insert.executeUpdate();
                    persisted.add(chapter.withId(SqliteSupport.generatedId(connection, insert)));
                } else {
                    update.setString(1, chapter.title());
                    update.setInt(2, chapter.sequence());
                    update.setLong(3, chapter.id());
                    update.setLong(4, journeyId);
                    SqliteSupport.requireUpdated(update.executeUpdate(), "chapter", chapter.id());
                    persisted.add(chapter);
                }
            }
        }
        return List.copyOf(persisted);
    }

    private List<LearnUnit> persistLearnUnits(
            Connection connection,
            long journeyId,
            Map<String, Long> chapterIds,
            List<LearnUnit> units) throws SQLException {
        ArrayList<LearnUnit> persisted = new ArrayList<LearnUnit>();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO learn_unit(journey_id, chapter_id, code, title, objective, content, sequence, prerequisite_codes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE learn_unit SET chapter_id = ?, title = ?, objective = ?, content = ?, sequence = ?, "
                             + "prerequisite_codes = ? WHERE id = ? AND journey_id = ?")) {
            for (LearnUnit unit : units) {
                Long chapterId = chapterIds.get(unit.chapterCode());
                if (chapterId == null) {
                    throw new IllegalStateException("missing persisted chapter: " + unit.chapterCode());
                }
                if (unit.id() == null) {
                    insert.setLong(1, journeyId);
                    insert.setLong(2, chapterId);
                    insert.setString(3, unit.code());
                    insert.setString(4, unit.title());
                    insert.setString(5, unit.objective());
                    insert.setString(6, unit.content());
                    insert.setInt(7, unit.sequence());
                    insert.setString(8, SqliteJson.write(unit.prerequisiteCodes()));
                    insert.executeUpdate();
                    persisted.add(unit.withId(SqliteSupport.generatedId(connection, insert)));
                } else {
                    update.setLong(1, chapterId);
                    update.setString(2, unit.title());
                    update.setString(3, unit.objective());
                    update.setString(4, unit.content());
                    update.setInt(5, unit.sequence());
                    update.setString(6, SqliteJson.write(unit.prerequisiteCodes()));
                    update.setLong(7, unit.id());
                    update.setLong(8, journeyId);
                    SqliteSupport.requireUpdated(update.executeUpdate(), "learn unit", unit.id());
                    persisted.add(unit);
                }
            }
        }
        return List.copyOf(persisted);
    }

    private List<Chapter> insertChapters(Connection connection, long journeyId, List<Chapter> chapters)
            throws SQLException {
        ArrayList<Chapter> persisted = new ArrayList<Chapter>();
        try (PreparedStatement statement = connection.prepareStatement(
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
        ArrayList<LearnUnit> persisted = new ArrayList<LearnUnit>();
        try (PreparedStatement statement = connection.prepareStatement(
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
        ArrayList<LearningPathItem> persisted = new ArrayList<LearningPathItem>();
        try (PreparedStatement statement = connection.prepareStatement(pathItemInsertSql(), Statement.RETURN_GENERATED_KEYS)) {
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
        ArrayList<LearningPathItem> persisted = new ArrayList<LearningPathItem>();
        try (PreparedStatement update = connection.prepareStatement(pathItemUpdateSql());
             PreparedStatement insert = connection.prepareStatement(pathItemInsertSql(), Statement.RETURN_GENERATED_KEYS)) {
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
        try (PreparedStatement statement = connection.prepareStatement(
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
        List<Chapter> chapters = readChapters(connection, journeyId);
        List<LearnUnit> units = readLearnUnits(connection, journeyId);
        List<LearningPathItem> items = readPathItems(connection, journeyId);
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
        ArrayList<Chapter> chapters = new ArrayList<Chapter>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, title, sequence FROM chapter WHERE journey_id = ? ORDER BY sequence, code")) {
            statement.setLong(1, journeyId);
            try (ResultSet result = statement.executeQuery()) {
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
        ArrayList<LearnUnit> units = new ArrayList<LearnUnit>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, title, objective, content, sequence, "
                        + "(SELECT code FROM chapter WHERE chapter.id = learn_unit.chapter_id) AS chapter_code, "
                        + "prerequisite_codes FROM learn_unit WHERE journey_id = ? ORDER BY sequence, id")) {
            statement.setLong(1, journeyId);
            try (ResultSet result = statement.executeQuery()) {
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
        ArrayList<LearningPathItem> items = new ArrayList<LearningPathItem>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, learn_unit_code, sequence, status, practice_verified, assessment_id, pass_reason, started_at, "
                        + "completed_at, updated_at "
                        + "FROM learning_path_item WHERE journey_id = ? ORDER BY sequence, id")) {
            statement.setLong(1, journeyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new LearningPathItem(
                            result.getLong("id"),
                            result.getString("learn_unit_code"),
                            result.getInt("sequence"),
                            LearningPathItemStatus.valueOf(result.getString("status")),
                            SqliteSupport.bool(result, "practice_verified"),
                            SqliteSupport.nullableLong(result, "assessment_id"),
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
                + "practice_verified, assessment_id, pass_reason, started_at, completed_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private String pathItemUpdateSql() {
        return "UPDATE learning_path_item SET sequence = ?, status = ?, practice_verified = ?, assessment_id = ?, pass_reason = ?, "
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
        SqliteSupport.nullableLong(statement, 7, item.assessmentId());
        statement.setString(8, item.passReason());
        statement.setString(9, SqliteSupport.instant(item.startedAt()));
        statement.setString(10, SqliteSupport.instant(item.completedAt()));
        statement.setString(11, item.updatedAt().toString());
    }

    private void bindPathItemUpdate(PreparedStatement statement, LearningPathItem item) throws SQLException {
        statement.setInt(1, item.sequence());
        statement.setString(2, item.status().name());
        statement.setInt(3, SqliteSupport.bool(item.practiceVerified()));
        SqliteSupport.nullableLong(statement, 4, item.assessmentId());
        statement.setString(5, item.passReason());
        statement.setString(6, SqliteSupport.instant(item.startedAt()));
        statement.setString(7, SqliteSupport.instant(item.completedAt()));
        statement.setString(8, item.updatedAt().toString());
        statement.setLong(9, item.id());
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }
}
