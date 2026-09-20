package com.db117.learnagent.language;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import com.db117.learnagent.learning.api.WorkspaceDescriptor;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.JourneyStatus;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.project.domain.Project;
import com.db117.learnagent.project.domain.ProjectRepository;
import com.db117.learnagent.workspace.api.WorkspaceContentRequest;
import com.db117.learnagent.workspace.api.WorkspaceResource;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceApplicationServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path dataDir;

    @Test
    void bootstrapInitializesCurrentConfirmedLearningWorkspace() throws Exception {
        var learner = new Learner(1L, "Alice", "TypeScript developer", CREATED_AT);
        var journey = new Journey(
                2L, learner.id(), "Build a TypeScript app", JourneyStatus.ACTIVE,
                CREATED_AT, null, 3L, true);
        var service = service(learner, journey, learningJourney(learner.id()));

        var workspace = service.ensureCurrentLearningWorkspace().orElseThrow();

        assertEquals(dataDir.resolve("journeys/2/workspace"), workspace.root());
        assertEquals("export {};", Files.readString(workspace.root().resolve("src/index.ts")));
        assertEquals("learning:2", WorkspaceDescriptor.from(workspace).reference());

        var resource = new WorkspaceResource(service, new WorkspaceManager(runtimeConfig()));
        resource.writeLearningFile(2, "src/main.ts", new WorkspaceContentRequest("export const answer = 42;"));
        assertEquals("export const answer = 42;",
                resource.readLearningFile(2, "src/main.ts").content());
    }

    @Test
    void rejectsLearningWorkspaceFromAnotherLearner() {
        var learner = new Learner(1L, "Alice", "TypeScript developer", CREATED_AT);
        var otherJourney = new Journey(
                9L, 2L, "Private goal", JourneyStatus.ACTIVE,
                CREATED_AT, null, 3L, true);
        var service = service(learner, otherJourney, learningJourney(2L));

        var error = assertThrows(LearningRequestException.class,
                () -> service.learningWorkspace(otherJourney.id()));

        assertEquals(404, error.status());
    }

    private WorkspaceApplicationService service(
            Learner learner, Journey journey, LearningJourney learningJourney) {
        var learnerRepository = new LearnerRepository() {
            @Override
            public Learner save(Learner value) {
                return value;
            }

            @Override
            public Optional<Learner> findById(long id) {
                return id == learner.id() ? Optional.of(learner) : Optional.empty();
            }

            @Override
            public Optional<Learner> findCurrent() {
                return Optional.of(learner);
            }
        };
        var journeyRepository = new JourneyRepository() {
            @Override
            public Journey save(Journey value) {
                return value;
            }

            @Override
            public Optional<Journey> findById(long id) {
                return id == journey.id() ? Optional.of(journey) : Optional.empty();
            }

            @Override
            public List<Journey> findByLearnerId(long learnerId) {
                return journey.learnerId() == learnerId ? List.of(journey) : List.of();
            }

            @Override
            public Optional<Journey> findCurrentByLearnerId(long learnerId) {
                return journey.current() && journey.learnerId() == learnerId
                        ? Optional.of(journey) : Optional.empty();
            }

            @Override
            public Journey selectCurrent(long journeyId, long learnerId) {
                return journey;
            }

            @Override
            public Journey attachLearningJourney(long journeyId, long learningJourneyId, long learnerId) {
                return journey;
            }
        };
        var learningJourneyRepository = new LearningJourneyRepository() {
            @Override
            public LearningJourney save(LearningJourney value) {
                return value;
            }

            @Override
            public void delete(long id) {
            }

            @Override
            public Optional<LearningJourney> findById(long id) {
                return id == 3L ? Optional.of(learningJourney) : Optional.empty();
            }

            @Override
            public Optional<LearningJourney> findActiveByLearnerAndLanguage(
                    long learnerId, String languagePackId) {
                return Optional.empty();
            }
        };
        ProjectRepository projectRepository = new ProjectRepository() {
            @Override
            public Project save(Project value) {
                return value;
            }

            @Override
            public Optional<Project> findById(long id) {
                return Optional.empty();
            }

            @Override
            public Optional<Project> findByJourneyId(long journeyId) {
                return Optional.empty();
            }
        };
        return new WorkspaceApplicationService(
                learnerRepository,
                journeyRepository,
                learningJourneyRepository,
                projectRepository,
                new LanguagePackCatalog(List.of(new TypeScriptLanguagePack())),
                new WorkspaceManager(runtimeConfig()));
    }

    private LearningJourney learningJourney(long learnerId) {
        var chapter = com.db117.learnagent.learning.domain.Chapter.create("basics", "Basics", 0);
        var unit = com.db117.learnagent.learning.domain.LearnUnit.create(
                "variables", "Variables", "Use values", "Variables content", 0, "basics", java.util.Set.of());
        return LearningJourney.create(
                learnerId, "typescript", "TypeScript Journey",
                List.of(chapter), List.of(unit), CREATED_AT).withId(3L);
    }

    private RuntimeConfig runtimeConfig() {
        return new RuntimeConfig() {
            @Override
            public String dataDir() {
                return dataDir.toString();
            }

            @Override
            public boolean memoryEnabled() {
                return false;
            }
        };
    }
}
