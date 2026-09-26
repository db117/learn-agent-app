package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.practice.domain.PracticeAssessment;
import com.db117.learnagent.practice.domain.PracticeAssessmentRepository;
import com.db117.learnagent.practice.domain.PracticeAssessmentVerdict;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/** Tutor 评估按次追加保存，并通过 ID 供 LearningPathItem 记录学习者确认。 */
@ApplicationScoped
public final class SqlitePracticeAssessmentRepository implements PracticeAssessmentRepository {
    private final DataSource dataSource;

    public SqlitePracticeAssessmentRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public PracticeAssessment save(PracticeAssessment assessment) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO practice_assessment(journey_id, learn_unit_id, practice_task_id, "
                             + "practice_attempt_id, verdict, rationale, workspace_digest, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            SqliteSupport.enableForeignKeys(connection);
            statement.setLong(1, assessment.journeyId());
            statement.setLong(2, assessment.learnUnitId());
            statement.setLong(3, assessment.practiceTaskId());
            statement.setLong(4, assessment.practiceAttemptId());
            statement.setString(5, assessment.verdict().name());
            statement.setString(6, assessment.rationale());
            statement.setString(7, assessment.workspaceDigest());
            statement.setString(8, assessment.createdAt().toString());
            statement.executeUpdate();
            return assessment.withId(SqliteSupport.generatedId(connection, statement));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save practice assessment", error);
        }
    }

    @Override
    public Optional<PracticeAssessment> findById(long id) {
        return query("WHERE id = ?", statement -> statement.setLong(1, id));
    }

    @Override
    public Optional<PracticeAssessment> findLatest(long journeyId, long learnUnitId) {
        return query("WHERE journey_id = ? AND learn_unit_id = ? ORDER BY id DESC LIMIT 1", statement -> {
            statement.setLong(1, journeyId);
            statement.setLong(2, learnUnitId);
        });
    }

    private Optional<PracticeAssessment> query(String suffix, Binder binder) {
        String sql = "SELECT id, journey_id, learn_unit_id, practice_task_id, practice_attempt_id, verdict, "
                + "rationale, workspace_digest, created_at FROM practice_assessment " + suffix;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAssessment(result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find practice assessment", error);
        }
    }

    private PracticeAssessment readAssessment(ResultSet result) throws SQLException {
        return new PracticeAssessment(
                result.getLong("id"),
                result.getLong("journey_id"),
                result.getLong("learn_unit_id"),
                result.getLong("practice_task_id"),
                result.getLong("practice_attempt_id"),
                PracticeAssessmentVerdict.valueOf(result.getString("verdict")),
                result.getString("rationale"),
                result.getString("workspace_digest"),
                Instant.parse(result.getString("created_at")));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
