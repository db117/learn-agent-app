package com.db117.learnagent;

import com.db117.learnagent.agent.runtime.TutorModel;
import com.db117.learnagent.config.ModelConfiguration;
import com.db117.learnagent.config.ModelConfigurationService;
import com.db117.learnagent.config.OpenAIProtocol;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
import com.db117.learnagent.persistence.sqlite.SqliteJourneyRepository;
import com.db117.learnagent.persistence.sqlite.SqliteLearnerRepository;
import com.db117.learnagent.persistence.sqlite.SqliteLearningJourneyRepository;
import com.db117.learnagent.persistence.sqlite.SqliteModelConfigurationRepository;
import com.db117.learnagent.persistence.sqlite.SqlitePracticeAssessmentRepository;
import com.db117.learnagent.persistence.sqlite.SqlitePracticeTaskRepository;
import com.db117.learnagent.persistence.sqlite.SqliteProjectRepository;
import com.db117.learnagent.persistence.sqlite.SqliteSchemaInitializer;
import com.db117.learnagent.practice.domain.PracticeAssessment;
import com.db117.learnagent.practice.domain.PracticeAssessmentVerdict;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.project.domain.Project;
import com.db117.learnagent.project.domain.ProjectEvidence;
import com.db117.learnagent.project.domain.ProjectMilestone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void modelConfigurationPersistsSettingsAndKeepsSecretsPrivate() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();

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
    void schemaVersionMismatchBacksUpDatabaseThenRecreatesCurrentSchema(@TempDir Path tempDirectory)
            throws Exception {
        Path databasePath = tempDirectory.resolve("learn-agent.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        SqliteLearnerRepository learners = new SqliteLearnerRepository(dataSource);
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            new SqliteSchemaInitializer(dataSource).initialize();
            Learner learner = learners.save(Learner.create("Alice", "Java engineer", T0));
            try (java.sql.Connection connection = dataSource.getConnection();
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("UPDATE schema_metadata SET schema_version = 11 WHERE id = 1");
            }

            new SqliteSchemaInitializer(dataSource).initialize();
            assertTrue(learners.findCurrent().isEmpty());
            try (java.sql.Connection connection = dataSource.getConnection();
                 java.sql.Statement statement = connection.createStatement();
                 java.sql.ResultSet result = statement.executeQuery(
                         "SELECT schema_version FROM schema_metadata WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals(SqliteSchemaInitializer.SCHEMA_VERSION, result.getInt(1));
            }

            Path backupPath;
            try (java.util.stream.Stream<Path> backups = Files.list(tempDirectory)) {
                backupPath = backups.filter(path -> path.getFileName().toString()
                                .startsWith("learn-agent.db.backup-"))
                        .findFirst()
                        .orElseThrow();
            }
            SQLiteDataSource backupDataSource = new SQLiteDataSource();
            backupDataSource.setUrl("jdbc:sqlite:" + backupPath);
            try (java.sql.Connection backupConnection = backupDataSource.getConnection();
                 java.sql.PreparedStatement statement = backupConnection.prepareStatement(
                         "SELECT display_name FROM learner WHERE id = ?")) {
                statement.setLong(1, learner.id());
                try (java.sql.ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("Alice", result.getString(1));
                }
            }
        }
    }

    @Test
    void assessmentAllowsCompletionWithoutPracticeVerification() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        SqliteLearnerRepository learners = new SqliteLearnerRepository(dataSource);
        SqliteLearningJourneyRepository journeys = new SqliteLearningJourneyRepository(dataSource);
        SqliteSchemaInitializer initializer = new SqliteSchemaInitializer(dataSource);
        try (java.sql.Connection anchor = dataSource.getConnection()) {
            initializer.initialize();
            Learner learner = learners.save(Learner.create("Alice", "TypeScript learner", T0));
            LearningJourney saved = journeys.save(outlineJourney(learner.id()));
            LearningJourney loaded = journeys.findById(saved.id()).orElseThrow();
            assertEquals(saved.pathItems().getFirst().id(), loaded.pathItems().getFirst().id());
            assertEquals(LearningPathItemStatus.CURRENT, loaded.currentItem().status());

            LearnUnit unit = loaded.learnUnit("variables");
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
                    loaded.acceptAssessment("variables", assessment.id(), false, T0.plusSeconds(1)));

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
