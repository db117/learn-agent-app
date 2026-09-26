package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.practice.domain.*;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PracticeTask 聚合的 SQLite 适配器；Attempt/Evidence 只追加不覆盖。 */
@ApplicationScoped
public class SqlitePracticeTaskRepository implements PracticeTaskRepository {
    private final DataSource dataSource;

    public SqlitePracticeTaskRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public PracticeTask save(PracticeTask task) {
        try (Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            connection.setAutoCommit(false);
            try {
                PracticeTask saved = task.id() == null ? insertNew(connection, task) : updateExisting(connection, task);
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save practice task", error);
        }
    }

    @Override
    public Optional<PracticeTask> findById(long id) {
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, journey_id, learn_unit_id, language_pack_id, type, title, description, difficulty, "
                             + "starter_template, choice_question, verification_policy, status, created_at "
                             + "FROM practice_task WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTask(connection, result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find practice task", error);
        }
    }

    @Override
    public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, journey_id, learn_unit_id, language_pack_id, type, title, description, difficulty, "
                             + "starter_template, choice_question, verification_policy, status, created_at "
                             + "FROM practice_task "
                             + "WHERE journey_id = ? AND learn_unit_id = ? ORDER BY id")) {
            statement.setLong(1, journeyId);
            statement.setLong(2, learnUnitId);
            try (ResultSet result = statement.executeQuery()) {
                ArrayList<PracticeTask> tasks = new ArrayList<PracticeTask>();
                while (result.next()) {
                    tasks.add(readTask(connection, result));
                }
                return List.copyOf(tasks);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find practice tasks", error);
        }
    }

    private PracticeTask insertNew(Connection connection, PracticeTask task) throws SQLException {
        // 先保存任务根，再按提交顺序追加客观证据。
        long taskId;
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO practice_task(journey_id, learn_unit_id, language_pack_id, type, title, description, "
                        + "difficulty, starter_template, choice_question, verification_policy, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bindTask(statement, task);
            statement.executeUpdate();
            taskId = SqliteSupport.generatedId(connection, statement);
        }
        List<PracticeAttempt> attempts = insertAttempts(connection, taskId, task.attempts());
        return PracticeTask.reconstitute(
                taskId,
                task.journeyId(),
                task.learnUnitId(),
                task.languagePackId(),
                task.type(),
                task.title(),
                task.description(),
                task.difficulty(),
                task.starterTemplate(),
                task.choiceQuestion(),
                task.verificationPolicy(),
                task.status(),
                task.createdAt(),
                attempts);
    }

    private PracticeTask updateExisting(Connection connection, PracticeTask task) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "UPDATE practice_task SET type = ?, title = ?, description = ?, difficulty = ?, starter_template = ?, "
                        + "choice_question = ?, verification_policy = ?, status = ? WHERE id = ? AND journey_id = ? "
                        + "AND learn_unit_id = ? AND language_pack_id = ?")) {
            statement.setString(1, task.type());
            statement.setString(2, task.title());
            statement.setString(3, task.description());
            statement.setInt(4, task.difficulty());
            statement.setString(5, task.starterTemplate());
            statement.setString(6, task.choiceQuestion() == null ? null : SqliteJson.write(task.choiceQuestion()));
            statement.setString(7, SqliteJson.write(task.verificationPolicy()));
            statement.setString(8, task.status().name());
            statement.setLong(9, task.id());
            statement.setLong(10, task.journeyId());
            statement.setLong(11, task.learnUnitId());
            statement.setString(12, task.languagePackId());
            SqliteSupport.requireUpdated(statement.executeUpdate(), "practice task", task.id());
        }
        List<PracticeAttempt> persistedAttempts = insertAttempts(connection, task.id(), task.attempts());
        return PracticeTask.reconstitute(
                task.id(),
                task.journeyId(),
                task.learnUnitId(),
                task.languagePackId(),
                task.type(),
                task.title(),
                task.description(),
                task.difficulty(),
                task.starterTemplate(),
                task.choiceQuestion(),
                task.verificationPolicy(),
                task.status(),
                task.createdAt(),
                persistedAttempts);
    }

    private void bindTask(java.sql.PreparedStatement statement, PracticeTask task) throws SQLException {
        statement.setLong(1, task.journeyId());
        statement.setLong(2, task.learnUnitId());
        statement.setString(3, task.languagePackId());
        statement.setString(4, task.type());
        statement.setString(5, task.title());
        statement.setString(6, task.description());
        statement.setInt(7, task.difficulty());
        statement.setString(8, task.starterTemplate());
        statement.setString(9, task.choiceQuestion() == null ? null : SqliteJson.write(task.choiceQuestion()));
        statement.setString(10, SqliteJson.write(task.verificationPolicy()));
        statement.setString(11, task.status().name());
        statement.setString(12, task.createdAt().toString());
    }

    private List<PracticeAttempt> insertAttempts(
            Connection connection, long taskId, List<PracticeAttempt> attempts) throws SQLException {
        ArrayList<PracticeAttempt> persisted = new ArrayList<PracticeAttempt>();
        try (java.sql.PreparedStatement attemptStatement = connection.prepareStatement(
                "INSERT INTO practice_attempt(practice_task_id, submitted_at) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS);
             java.sql.PreparedStatement evidenceStatement = connection.prepareStatement(
                     "INSERT INTO practice_evidence(attempt_id, compile_passed, tests_passed, test_count, lint_passed, "
                             + "runtime_result, submitted_files, verified_at, choice_correct, workspace_digest) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (PracticeAttempt attempt : attempts) {
                if (attempt.id() != null) {
                    // 已持久化 Attempt 是不可变历史，不能因再次保存聚合而复制。
                    persisted.add(attempt);
                    continue;
                }
                attemptStatement.setLong(1, taskId);
                attemptStatement.setString(2, attempt.submittedAt().toString());
                attemptStatement.executeUpdate();
                long attemptId = SqliteSupport.generatedId(connection, attemptStatement);
                PracticeEvidence evidence = attempt.evidence();
                evidenceStatement.setLong(1, attemptId);
                evidenceStatement.setInt(2, SqliteSupport.bool(evidence.compilePassed()));
                evidenceStatement.setInt(3, SqliteSupport.bool(evidence.testsPassed()));
                evidenceStatement.setInt(4, evidence.testCount());
                evidenceStatement.setInt(5, SqliteSupport.bool(evidence.lintPassed()));
                evidenceStatement.setString(6, evidence.runtimeResult().name());
                evidenceStatement.setString(7, SqliteJson.write(evidence.submittedFiles()));
                evidenceStatement.setString(8, SqliteSupport.instant(evidence.verifiedAt()));
                evidenceStatement.setInt(9, SqliteSupport.bool(evidence.choiceCorrect()));
                evidenceStatement.setString(10, evidence.workspaceDigest());
                evidenceStatement.executeUpdate();
                persisted.add(attempt.withId(attemptId));
            }
        }
        return List.copyOf(persisted);
    }

    private PracticeTask readTask(Connection connection, ResultSet result) throws SQLException {
        long taskId = result.getLong("id");
        ArrayList<PracticeAttempt> attempts = new ArrayList<PracticeAttempt>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT pa.id, pa.submitted_at, pe.compile_passed, pe.tests_passed, pe.test_count, pe.lint_passed, "
                        + "pe.runtime_result, pe.submitted_files, pe.verified_at, pe.choice_correct, pe.workspace_digest "
                        + "FROM practice_attempt pa JOIN practice_evidence pe ON pe.attempt_id = pa.id "
                        + "WHERE pa.practice_task_id = ? ORDER BY pa.id")) {
            statement.setLong(1, taskId);
            try (ResultSet evidenceResult = statement.executeQuery()) {
                while (evidenceResult.next()) {
                    PracticeEvidence evidence = new PracticeEvidence(
                            SqliteSupport.bool(evidenceResult, "compile_passed"),
                            SqliteSupport.bool(evidenceResult, "tests_passed"),
                            evidenceResult.getInt("test_count"),
                            SqliteSupport.bool(evidenceResult, "lint_passed"),
                            RuntimeResult.valueOf(evidenceResult.getString("runtime_result")),
                            SqliteJson.strings(evidenceResult.getString("submitted_files")),
                            SqliteSupport.parseInstant(evidenceResult, "verified_at"),
                            SqliteSupport.bool(evidenceResult, "choice_correct"),
                            evidenceResult.getString("workspace_digest"));
                    attempts.add(new PracticeAttempt(
                            evidenceResult.getLong("id"),
                            Instant.parse(evidenceResult.getString("submitted_at")),
                            evidence));
                }
            }
        }
        return PracticeTask.reconstitute(
                taskId,
                result.getLong("journey_id"),
                result.getLong("learn_unit_id"),
                result.getString("language_pack_id"),
                result.getString("type"),
                result.getString("title"),
                result.getString("description"),
                result.getInt("difficulty"),
                result.getString("starter_template"),
                SqliteJson.choiceQuestion(result.getString("choice_question")),
                SqliteJson.verificationPolicy(result.getString("verification_policy")),
                PracticeTaskStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("created_at")),
                attempts);
    }
}
