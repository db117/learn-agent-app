package com.db117.learnagent;

import com.db117.learnagent.agent.runtime.TutorModel;
import com.db117.learnagent.config.ModelConfiguration;
import com.db117.learnagent.config.ModelConfigurationService;
import com.db117.learnagent.config.OpenAIProtocol;
import com.db117.learnagent.learning.domain.*;
import com.db117.learnagent.persistence.sqlite.*;
import com.db117.learnagent.practice.domain.*;
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

import static org.junit.jupiter.api.Assertions.*;

class SqliteRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void repositoriesRoundTripAggregatesAndEnforceJourneyBoundaries() throws Exception {
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            SqliteLearnerRepository learnerRepository = new SqliteLearnerRepository(dataSource);
            SqliteLearningJourneyRepository journeyRepository = new SqliteLearningJourneyRepository(dataSource);
            SqlitePracticeTaskRepository practiceRepository = new SqlitePracticeTaskRepository(dataSource);
            SqliteProjectRepository projectRepository = new SqliteProjectRepository(dataSource);

            Learner learner = learnerRepository.save(Learner.create("Alice", "Java engineer with ten years of experience", T0));
            assertNotNull(learner.id());
            LearningJourney savedJourney = journeyRepository.save(journey(learner.id()));
            assertNotNull(savedJourney.id());

            LearningJourney loadedJourney = journeyRepository.findById(savedJourney.id()).orElseThrow();
            assertEquals(List.of("variables", "loops"), loadedJourney.pathItems().stream()
                    .map(item -> item.learnUnitCode()).toList());
            LearningJourney movedJourney = journeyRepository.save(
                    loadedJourney.recordPracticeVerified("variables", T0.plusSeconds(3)));
            LearningJourney movedRoundTrip = journeyRepository.findActiveByLearnerAndLanguage(
                    learner.id(), "java").orElseThrow();
            assertEquals(movedJourney.id(), movedRoundTrip.id());
            assertEquals(LearningPathItemStatus.COMPLETED, movedRoundTrip.pathItems().get(0).status());

            Long learnUnitId = savedJourney.learnUnit("variables").id();
            PracticeTask task = PracticeTask.create(
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
            PracticeTask failedTask = task.recordAttempt(PracticeAttempt.submit(
                    new PracticeEvidence(false, false, 0, false, RuntimeResult.FAILED,
                            List.of("src/Main.java"), T0.plusSeconds(2)),
                    T0.plusSeconds(2)));
            PracticeTask savedTask = practiceRepository.save(failedTask);
            PracticeTask taskRoundTrip = practiceRepository.findById(savedTask.id()).orElseThrow();
            assertEquals(1, taskRoundTrip.attempts().size());

            Project project = Project.create(
                    savedJourney.id(),
                    "Todo app",
                    List.of(ProjectMilestone.create("m1", "First milestone", 0)),
                    T0);
            Project savedProject = projectRepository.save(project);
            Project activeProject = projectRepository.save(savedProject.activate().startMilestone("m1"));
            Project completedProject = projectRepository.save(activeProject.recordEvidence(
                    "m1",
                    new ProjectEvidence("workspace://todo", "verified", true, T0.plusSeconds(3))));
            Project projectRoundTrip = projectRepository.findByJourneyId(savedJourney.id()).orElseThrow();
            assertEquals(completedProject.id(), projectRoundTrip.id());
            assertEquals(1, projectRoundTrip.milestones().get(0).evidence().size());

            LearningJourney sameLanguageJourney = journeyRepository.save(journey(learner.id()));
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
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            SqliteLearnerRepository learnerRepository = new SqliteLearnerRepository(dataSource);
            SqliteJourneyRepository journeyRepository = new SqliteJourneyRepository(dataSource);
            SqliteLearningJourneyRepository learningJourneyRepository = new SqliteLearningJourneyRepository(dataSource);

            Learner learner = learnerRepository.save(Learner.create("Alice", "Java engineer", T0));
            Journey first = journeyRepository.selectCurrent(
                    journeyRepository.save(Journey.create(learner.id(), "Learn Java", T0)).id(), learner.id());
            Journey second = journeyRepository.save(Journey.create(learner.id(), "Build a service", T0.plusSeconds(1)));

            assertEquals(first.id(), journeyRepository.findCurrentByLearnerId(learner.id()).orElseThrow().id());
            LearningJourney path = learningJourneyRepository.save(journey(learner.id()));
            Journey linked = journeyRepository.attachLearningJourney(first.id(), path.id(), learner.id());
            assertEquals(path.id(), linked.learningJourneyId());

            Journey selectedSecond = journeyRepository.selectCurrent(second.id(), learner.id());
            assertTrue(selectedSecond.current());
            assertFalse(journeyRepository.findById(first.id()).orElseThrow().current());
            assertEquals(2, journeyRepository.findByLearnerId(learner.id()).size());
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void journeyRepositoryPersistsContentGeneratedAfterPathConfirmation() {
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            SqliteLearnerRepository learnerRepository = new SqliteLearnerRepository(dataSource);
            SqliteLearningJourneyRepository journeyRepository = new SqliteLearningJourneyRepository(dataSource);
            Learner learner = learnerRepository.save(Learner.create("Alice", "Java engineer", T0));
            LearningJourney saved = journeyRepository.save(outlineJourney(learner.id()));

            journeyRepository.save(saved.materializeLearnUnitContent(
                    "variables", "## Concept\nVariables\n## Practice\nFix variables"));

            LearningJourney loaded = journeyRepository.findById(saved.id()).orElseThrow();
            assertEquals("## Concept\nVariables\n## Practice\nFix variables",
                    loaded.learnUnit("variables").content());
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void initializerRejectsUnknownExistingBusinessTables() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE legacy_business(id INTEGER)");
            assertThrows(IllegalStateException.class,
                    () -> new SqliteSchemaInitializer(dataSource).initialize());
        }
    }

    @Test
    void modelConfigurationUpgradePreservesLearningDataAndAppliesNewSettings() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            SqliteLearnerRepository learnerRepository = new SqliteLearnerRepository(dataSource);
            learnerRepository.save(Learner.create("Alice", "TypeScript learner", T0));

            try (java.sql.Connection connection = dataSource.getConnection();
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE model_configuration");
                statement.execute("UPDATE schema_metadata SET schema_version = 7 WHERE id = 1");
            }
            new SqliteSchemaInitializer(dataSource).initialize();
            assertEquals("Alice", learnerRepository.findCurrent().orElseThrow().displayName());

            SqliteModelConfigurationRepository repository = new SqliteModelConfigurationRepository(dataSource);
            ModelConfiguration previous = new ModelConfiguration(
                    "old-model", "https://one.example/v1", "test-secret", OpenAIProtocol.CHAT_COMPLETIONS);
            repository.save(previous);
            TutorModel tutorModel = new TutorModel();
            ModelConfigurationService service = new ModelConfigurationService(repository, tutorModel);

            ModelConfigurationService.ConfigurationView saved = service.save(
                    "new-model", OpenAIProtocol.CHAT_COMPLETIONS, "https://one.example/v1", "", false);
            assertTrue(saved.configured());
            assertEquals("new-model", tutorModel.getModelName());
            assertEquals("test-secret", repository.find().orElseThrow().apiKey());
            assertFalse(saved.toString().contains("test-secret"));

            service.save(
                    "local-model", OpenAIProtocol.CHAT_COMPLETIONS, "http://127.0.0.1:9000/v1", "", false);
            assertEquals("", repository.find().orElseThrow().apiKey());
            assertEquals("local-model", tutorModel.getModelName());
        }
    }

    @Test
    void v9PathMigrationPreservesCurrentItemAndAllowsAssessmentOnlyCompletion() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        SqliteLearnerRepository learners = new SqliteLearnerRepository(dataSource);
        SqliteLearningJourneyRepository journeys = new SqliteLearningJourneyRepository(dataSource);
        SqliteSchemaInitializer initializer = new SqliteSchemaInitializer(dataSource);
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            initializer.initialize();
            Learner learner = learners.save(Learner.create("Alice", "TypeScript learner", T0));
            LearningJourney saved = journeys.save(outlineJourney(learner.id()));

            try (java.sql.Connection connection = dataSource.getConnection();
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("DROP VIEW mastery");
                statement.execute("DROP INDEX uq_current_path_item");
                statement.execute("ALTER TABLE learning_path_item RENAME TO learning_path_item_v10");
                statement.execute("""
                        CREATE TABLE learning_path_item (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            journey_id INTEGER NOT NULL REFERENCES learning_journey(id) ON DELETE CASCADE,
                            learn_unit_id INTEGER NOT NULL,
                            learn_unit_code TEXT NOT NULL,
                            sequence INTEGER NOT NULL CHECK (sequence >= 0),
                            status TEXT NOT NULL CHECK (status IN ('PENDING', 'CURRENT', 'COMPLETED', 'SKIPPED')),
                            practice_verified INTEGER NOT NULL CHECK (practice_verified IN (0, 1)),
                            pass_reason TEXT,
                            started_at TEXT,
                            completed_at TEXT,
                            updated_at TEXT NOT NULL,
                            CHECK (status <> 'COMPLETED' OR (practice_verified = 1 AND completed_at IS NOT NULL)),
                            UNIQUE (journey_id, learn_unit_id),
                            FOREIGN KEY (journey_id, learn_unit_id) REFERENCES learn_unit(journey_id, id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO learning_path_item(
                            id, journey_id, learn_unit_id, learn_unit_code, sequence, status, practice_verified,
                            pass_reason, started_at, completed_at, updated_at)
                        SELECT id, journey_id, learn_unit_id, learn_unit_code, sequence, status, practice_verified,
                               pass_reason, started_at, completed_at, updated_at
                        FROM learning_path_item_v10
                        """);
                statement.execute("DROP TABLE learning_path_item_v10");
                statement.execute("""
                        CREATE UNIQUE INDEX uq_current_path_item
                        ON learning_path_item(journey_id)
                        WHERE status = 'CURRENT'
                        """);
                statement.execute("""
                        CREATE VIEW mastery AS
                        SELECT journey_id, learn_unit_id,
                               CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END AS mastered
                        FROM learning_path_item
                        """);
                statement.execute("UPDATE schema_metadata SET schema_version = 9 WHERE id = 1");
            }

            initializer.initialize();
            LearningJourney migrated = journeys.findById(saved.id()).orElseThrow();
            assertEquals(saved.pathItems().getFirst().id(), migrated.pathItems().getFirst().id());
            assertEquals(LearningPathItemStatus.CURRENT, migrated.currentItem().status());

            LearnUnit unit = migrated.learnUnit("variables");
            SqlitePracticeTaskRepository tasks = new SqlitePracticeTaskRepository(dataSource);
            PracticeTask task = tasks.save(PracticeTask.create(
                    saved.id(), unit.id(), "typescript", "CODE", "变量练习", "理解变量", 1, "",
                    new VerificationPolicy(true, true, false, false), T0));
            PracticeEvidence failedEvidence = new PracticeEvidence(
                    false, false, 0, false, RuntimeResult.NOT_RUN, List.of(), null);
            PracticeTask submitted = tasks.save(task.recordAttempt(PracticeAttempt.submit(failedEvidence, T0)));
            PracticeAssessment assessment = new SqlitePracticeAssessmentRepository(dataSource).save(
                    PracticeAssessment.create(saved.id(), unit.id(), task.id(), submitted.attempts().getFirst().id(),
                            PracticeAssessmentVerdict.READY, "理解说明足以通过", "a".repeat(64), T0));

            LearningJourney completed = journeys.save(
                    migrated.acceptAssessment("variables", assessment.id(), false, T0.plusSeconds(1)));

            assertEquals(LearningPathItemStatus.COMPLETED, completed.pathItems().getFirst().status());
            assertFalse(completed.pathItems().getFirst().practiceVerified());
            assertEquals(assessment.id(), completed.pathItems().getFirst().assessmentId());
        }
    }

    @Test
    void routeChangesRemoveChaptersThatNoLongerContainUnits() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            SqliteLearnerRepository learners = new SqliteLearnerRepository(dataSource);
            SqliteLearningJourneyRepository journeys = new SqliteLearningJourneyRepository(dataSource);
            Learner learner = learners.save(Learner.create("Alice", "TypeScript learner", T0));
            LearningJourney saved = journeys.save(outlineJourney(learner.id()));
            LearningJourney revised = saved.replan(
                    List.of(Chapter.create("foundation", "Foundation", 0)),
                    List.of(LearnUnit.create(
                            "variables", "Values", "Use values", "", 0, "foundation", Set.of())),
                    T0.plusSeconds(1));

            LearningJourney loaded = journeys.save(revised);

            assertEquals(List.of("foundation"), loaded.chapters().stream().map(Chapter::code).toList());
        }
    }

    private SQLiteDataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:step2-" + UUID.randomUUID()
                + "?mode=memory&cache=shared");
        return dataSource;
    }

    private LearningJourney journey(long learnerId) {
        Chapter chapter = Chapter.create("basics", "Basics", 0);
        LearnUnit variables = LearnUnit.create(
                "variables", "Variables", "Use values", "Variables content", 0, "basics", Set.of());
        LearnUnit loops = LearnUnit.create(
                "loops", "Loops", "Repeat work", "Loops content", 1, "basics", Set.of("variables"));
        return LearningJourney.create(
                learnerId,
                "java",
                "Java Journey",
                List.of(chapter),
                List.of(loops, variables),
                T0);
    }

    private LearningJourney outlineJourney(long learnerId) {
        Chapter chapter = Chapter.create("basics", "Basics", 0);
        LearnUnit variables = LearnUnit.create(
                "variables", "Variables", "Use values", "", 0, "basics", Set.of());
        return LearningJourney.create(
                learnerId,
                "java",
                "Java Journey",
                List.of(chapter),
                List.of(variables),
                T0);
    }
}
