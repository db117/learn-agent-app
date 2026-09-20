package com.db117.learnagent;

import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
import com.db117.learnagent.persistence.sqlite.SqliteJourneyRepository;
import com.db117.learnagent.persistence.sqlite.SqliteLearnerRepository;
import com.db117.learnagent.persistence.sqlite.SqliteLearningJourneyRepository;
import com.db117.learnagent.persistence.sqlite.SqlitePracticeTaskRepository;
import com.db117.learnagent.persistence.sqlite.SqliteProjectRepository;
import com.db117.learnagent.persistence.sqlite.SqliteSchemaInitializer;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.project.domain.Project;
import com.db117.learnagent.project.domain.ProjectEvidence;
import com.db117.learnagent.project.domain.ProjectMilestone;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void repositoriesRoundTripAggregatesAndEnforceJourneyBoundaries() throws Exception {
        var dataSource = dataSource();
        try (var anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            var learnerRepository = new SqliteLearnerRepository(dataSource);
            var journeyRepository = new SqliteLearningJourneyRepository(dataSource);
            var practiceRepository = new SqlitePracticeTaskRepository(dataSource);
            var projectRepository = new SqliteProjectRepository(dataSource);

            var learner = learnerRepository.save(Learner.create("Alice", "Java engineer with ten years of experience", T0));
            assertNotNull(learner.id());
            var savedJourney = journeyRepository.save(journey(learner.id()));
            assertNotNull(savedJourney.id());

            var loadedJourney = journeyRepository.findById(savedJourney.id()).orElseThrow();
            assertEquals(List.of("variables", "loops"), loadedJourney.pathItems().stream()
                    .map(item -> item.learnUnitCode()).toList());
            var movedJourney = journeyRepository.save(
                    loadedJourney.recordPracticeVerified("variables", T0.plusSeconds(3)));
            var movedRoundTrip = journeyRepository.findActiveByLearnerAndLanguage(
                    learner.id(), "java").orElseThrow();
            assertEquals(movedJourney.id(), movedRoundTrip.id());
            assertEquals(LearningPathItemStatus.COMPLETED, movedRoundTrip.pathItems().get(0).status());

            var learnUnitId = savedJourney.learnUnit("variables").id();
            var task = PracticeTask.create(
                    savedJourney.id(),
                    learnUnitId,
                    "java",
                    "CODE",
                    "Fix variables",
                    "Make it compile",
                    1,
                    "class Main {}",
                    new VerificationPolicy(true, false, false, false),
                    T0);
            var failedTask = task.recordAttempt(PracticeAttempt.submit(
                    new PracticeEvidence(false, false, 0, false, RuntimeResult.FAILED,
                            List.of("src/Main.java"), T0.plusSeconds(2)),
                    T0.plusSeconds(2)));
            var savedTask = practiceRepository.save(failedTask);
            var taskRoundTrip = practiceRepository.findById(savedTask.id()).orElseThrow();
            assertEquals(1, taskRoundTrip.attempts().size());

            var project = Project.create(
                    savedJourney.id(),
                    "Todo app",
                    List.of(ProjectMilestone.create("m1", "First milestone", 0)),
                    T0);
            var savedProject = projectRepository.save(project);
            var activeProject = projectRepository.save(savedProject.activate().startMilestone("m1"));
            var completedProject = projectRepository.save(activeProject.recordEvidence(
                    "m1",
                    new ProjectEvidence("workspace://todo", "verified", true, T0.plusSeconds(3))));
            var projectRoundTrip = projectRepository.findByJourneyId(savedJourney.id()).orElseThrow();
            assertEquals(completedProject.id(), projectRoundTrip.id());
            assertEquals(1, projectRoundTrip.milestones().get(0).evidence().size());

            var sameLanguageJourney = journeyRepository.save(journey(learner.id()));
            assertNotEquals(savedJourney.id(), sameLanguageJourney.id());
            assertThrows(IllegalStateException.class,
                    () -> projectRepository.save(Project.create(
                            savedJourney.id(),
                            "Second project",
                            List.of(ProjectMilestone.create("m1", "Duplicate", 0)),
                            T0)));
            assertTrue(journeyRepository.findById(savedJourney.id()).isPresent());
        }
    }

    @Test
    void journeyRepositoryPersistsCurrentSelectionAndOneToOnePathLink() {
        var dataSource = dataSource();
        try (var anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            var learnerRepository = new SqliteLearnerRepository(dataSource);
            var journeyRepository = new SqliteJourneyRepository(dataSource);
            var learningJourneyRepository = new SqliteLearningJourneyRepository(dataSource);

            var learner = learnerRepository.save(Learner.create("Alice", "Java engineer", T0));
            var first = journeyRepository.selectCurrent(
                    journeyRepository.save(Journey.create(learner.id(), "Learn Java", T0)).id(), learner.id());
            var second = journeyRepository.save(Journey.create(learner.id(), "Build a service", T0.plusSeconds(1)));

            assertEquals(first.id(), journeyRepository.findCurrentByLearnerId(learner.id()).orElseThrow().id());
            var path = learningJourneyRepository.save(journey(learner.id()));
            var linked = journeyRepository.attachLearningJourney(first.id(), path.id(), learner.id());
            assertEquals(path.id(), linked.learningJourneyId());

            var selectedSecond = journeyRepository.selectCurrent(second.id(), learner.id());
            assertTrue(selectedSecond.current());
            assertFalse(journeyRepository.findById(first.id()).orElseThrow().current());
            assertEquals(2, journeyRepository.findByLearnerId(learner.id()).size());
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void initializerRejectsUnknownExistingBusinessTables() throws SQLException {
        var dataSource = dataSource();
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE legacy_business(id INTEGER)");
            assertThrows(IllegalStateException.class,
                    () -> new SqliteSchemaInitializer(dataSource).initialize());
        }
    }

    private SQLiteDataSource dataSource() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:step2-" + UUID.randomUUID()
                + "?mode=memory&cache=shared");
        return dataSource;
    }

    private LearningJourney journey(long learnerId) {
        var chapter = Chapter.create("basics", "Basics", 0);
        var variables = LearnUnit.create(
                "variables", "Variables", "Use values", "Variables content", 0, "basics", Set.of());
        var loops = LearnUnit.create(
                "loops", "Loops", "Repeat work", "Loops content", 1, "basics", Set.of("variables"));
        return LearningJourney.create(
                learnerId,
                "java",
                "Java Journey",
                List.of(chapter),
                List.of(loops, variables),
                T0);
    }
}
