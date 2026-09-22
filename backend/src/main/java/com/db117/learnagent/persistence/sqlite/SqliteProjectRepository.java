package com.db117.learnagent.persistence.sqlite;

import com.db117.learnagent.project.domain.Project;
import com.db117.learnagent.project.domain.ProjectEvidence;
import com.db117.learnagent.project.domain.ProjectMilestone;
import com.db117.learnagent.project.domain.ProjectMilestoneStatus;
import com.db117.learnagent.project.domain.ProjectRepository;
import com.db117.learnagent.project.domain.ProjectStatus;
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

/** Project 聚合的 SQLite 适配器；Milestone 证据只追加，不覆盖既有历史。 */
@ApplicationScoped
public class SqliteProjectRepository implements ProjectRepository {
    private final DataSource dataSource;

    public SqliteProjectRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Project save(Project project) {
        try (Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            connection.setAutoCommit(false);
            try {
                Project saved = project.id() == null
                        ? insertNew(connection, project)
                        : updateExisting(connection, project);
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to save project", error);
        }
    }

    @Override
    public Optional<Project> findById(long id) {
        try (Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return readProject(connection, "WHERE id = ?", statement -> statement.setLong(1, id));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find project", error);
        }
    }

    @Override
    public Optional<Project> findByJourneyId(long journeyId) {
        try (Connection connection = dataSource.getConnection()) {
            SqliteSupport.enableForeignKeys(connection);
            return readProject(connection, "WHERE journey_id = ?", statement -> statement.setLong(1, journeyId));
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to find project by journey", error);
        }
    }

    private Project insertNew(Connection connection, Project project) throws SQLException {
        long projectId;
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO project(journey_id, title, status, created_at, completed_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, project.journeyId());
            statement.setString(2, project.title());
            statement.setString(3, project.status().name());
            statement.setString(4, project.createdAt().toString());
            statement.setString(5, SqliteSupport.instant(project.completedAt()));
            statement.executeUpdate();
            projectId = SqliteSupport.generatedId(connection, statement);
        }
        List<ProjectMilestone> persistedMilestones = insertMilestones(connection, projectId, project.milestones());
        return Project.reconstitute(
                projectId,
                project.journeyId(),
                project.title(),
                project.status(),
                project.createdAt(),
                project.completedAt(),
                persistedMilestones);
    }

    private Project updateExisting(Connection connection, Project project) throws SQLException {
        long projectId = project.id();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "UPDATE project SET title = ?, status = ?, completed_at = ? WHERE id = ? AND journey_id = ?")) {
            statement.setString(1, project.title());
            statement.setString(2, project.status().name());
            statement.setString(3, SqliteSupport.instant(project.completedAt()));
            statement.setLong(4, projectId);
            statement.setLong(5, project.journeyId());
            SqliteSupport.requireUpdated(statement.executeUpdate(), "project", projectId);
        }
        // Project 没有删除或替换 Milestone 的领域操作，保存时拒绝伪造未持久化节点。
        if (project.milestones().stream().anyMatch(milestone -> milestone.id() == null)) {
            throw new IllegalStateException("persisted project milestones cannot be replaced or added");
        }
        List<ProjectMilestone> persistedMilestones = updateMilestones(connection, project.milestones());
        return Project.reconstitute(
                projectId,
                project.journeyId(),
                project.title(),
                project.status(),
                project.createdAt(),
                project.completedAt(),
                persistedMilestones);
    }

    private List<ProjectMilestone> insertMilestones(
            Connection connection, long projectId, List<ProjectMilestone> milestones) throws SQLException {
        ArrayList<ProjectMilestone> persisted = new ArrayList<ProjectMilestone>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO project_milestone(project_id, code, title, sequence, status) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (ProjectMilestone milestone : milestones) {
                statement.setLong(1, projectId);
                statement.setString(2, milestone.code());
                statement.setString(3, milestone.title());
                statement.setInt(4, milestone.sequence());
                statement.setString(5, milestone.status().name());
                statement.executeUpdate();
                long milestoneId = SqliteSupport.generatedId(connection, statement);
                insertEvidence(connection, milestoneId, milestone.evidence());
                persisted.add(ProjectMilestone.reconstitute(
                        milestoneId,
                        milestone.code(),
                        milestone.title(),
                        milestone.sequence(),
                        milestone.status(),
                        milestone.evidence()));
            }
        }
        return List.copyOf(persisted);
    }

    private List<ProjectMilestone> updateMilestones(
            Connection connection, List<ProjectMilestone> milestones) throws SQLException {
        ArrayList<ProjectMilestone> persisted = new ArrayList<ProjectMilestone>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "UPDATE project_milestone SET code = ?, title = ?, sequence = ?, status = ? WHERE id = ?")) {
            for (ProjectMilestone milestone : milestones) {
                statement.setString(1, milestone.code());
                statement.setString(2, milestone.title());
                statement.setInt(3, milestone.sequence());
                statement.setString(4, milestone.status().name());
                statement.setLong(5, milestone.id());
                SqliteSupport.requireUpdated(statement.executeUpdate(), "project milestone", milestone.id());

                List<ProjectEvidence> existingEvidence = readEvidence(connection, milestone.id());
                if (existingEvidence.size() > milestone.evidence().size()
                        || !milestone.evidence().subList(0, existingEvidence.size()).equals(existingEvidence)) {
                    throw new IllegalStateException("project evidence history cannot be changed or removed");
                }
                // Evidence 没有可变字段；按已存在前缀只补写新提交，保持追加顺序。
                insertEvidence(connection, milestone.id(),
                        milestone.evidence().subList(existingEvidence.size(), milestone.evidence().size()));
                persisted.add(ProjectMilestone.reconstitute(
                        milestone.id(),
                        milestone.code(),
                        milestone.title(),
                        milestone.sequence(),
                        milestone.status(),
                        milestone.evidence()));
            }
        }
        return List.copyOf(persisted);
    }

    private void insertEvidence(Connection connection, long milestoneId, List<ProjectEvidence> evidence)
            throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO project_evidence(milestone_id, artifact_reference, verification_summary, passed, verified_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (ProjectEvidence item : evidence) {
                statement.setLong(1, milestoneId);
                statement.setString(2, item.artifactReference());
                statement.setString(3, item.verificationSummary());
                statement.setInt(4, SqliteSupport.bool(item.passed()));
                statement.setString(5, item.verifiedAt().toString());
                statement.executeUpdate();
            }
        }
    }

    private Optional<Project> readProject(Connection connection, String predicate, SqlBinder binder)
            throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT id, journey_id, title, status, created_at, completed_at FROM project " + predicate)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProject(connection, result)) : Optional.empty();
            }
        }
    }

    private Project readProject(Connection connection, ResultSet result) throws SQLException {
        long projectId = result.getLong("id");
        ArrayList<ProjectMilestone> milestones = new ArrayList<ProjectMilestone>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, title, sequence, status FROM project_milestone "
                        + "WHERE project_id = ? ORDER BY sequence, code")) {
            statement.setLong(1, projectId);
            try (ResultSet milestoneResult = statement.executeQuery()) {
                while (milestoneResult.next()) {
                    long milestoneId = milestoneResult.getLong("id");
                    milestones.add(ProjectMilestone.reconstitute(
                            milestoneId,
                            milestoneResult.getString("code"),
                            milestoneResult.getString("title"),
                            milestoneResult.getInt("sequence"),
                            ProjectMilestoneStatus.valueOf(milestoneResult.getString("status")),
                            readEvidence(connection, milestoneId)));
                }
            }
        }
        return Project.reconstitute(
                projectId,
                result.getLong("journey_id"),
                result.getString("title"),
                ProjectStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("created_at")),
                SqliteSupport.parseInstant(result, "completed_at"),
                milestones);
    }

    private List<ProjectEvidence> readEvidence(Connection connection, long milestoneId) throws SQLException {
        ArrayList<ProjectEvidence> evidence = new ArrayList<ProjectEvidence>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT artifact_reference, verification_summary, passed, verified_at "
                        + "FROM project_evidence WHERE milestone_id = ? ORDER BY id")) {
            statement.setLong(1, milestoneId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    evidence.add(new ProjectEvidence(
                            result.getString("artifact_reference"),
                            result.getString("verification_summary"),
                            result.getInt("passed") != 0,
                            Instant.parse(result.getString("verified_at"))));
                }
            }
        }
        return List.copyOf(evidence);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }
}
