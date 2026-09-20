package com.db117.learnagent;

import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RuntimeSkeletonTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PLAN = """
            {
              "chapters": [
                {
                  "code": "basics",
                  "title": "基础",
                  "units": [
                    {
                      "code": "variables",
                      "title": "变量与类型",
                      "objective": "能够声明变量并理解基本类型",
                      "concept": "变量保存数据。",
                      "example": "export const answer: number = 42;",
                      "practice": "修复变量的类型错误。"
                    },
                    {
                      "code": "functions",
                      "title": "函数",
                      "objective": "能够声明带类型的函数",
                      "concept": "函数描述可复用的行为。",
                      "example": "const add = (a: number, b: number) => a + b;",
                      "practice": "为函数补充类型。"
                    }
                  ]
                }
              ]
            }
            """;

    @Inject
    LearnerRepository learnerRepository;

    @Inject
    JourneyRepository journeyRepository;

    @Inject
    LearningJourneyRepository learningJourneyRepository;

    @TestHTTPResource("/health")
    URL healthUrl;

    @TestHTTPResource("/api/tutor/sessions")
    URL tutorSessionsUrl;

    @TestHTTPResource("/api/tutor/sessions/missing/messages")
    URL missingSessionMessagesUrl;

    @TestHTTPResource("/api/bootstrap")
    URL bootstrapUrl;

    @Test
    void healthChecksTheSqliteConnection() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(healthUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
        assertTrue(response.body().contains("\"database\":\"UP\""));
    }

    @Test
    void tutorApiReturnsStableValidationErrors() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(tutorSessionsUrl.toURI())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, response.statusCode());
        assertTrue(response.body().contains("INVALID_SESSION_REQUEST"));
    }

    @Test
    void tutorMessageRequiresAnExplicitlyRestoredSession() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(missingSessionMessagesUrl.toURI())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"turnId\":\"turn-1\",\"text\":\"hello\"}",
                                StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, response.statusCode());
        assertTrue(response.body().contains("SESSION_NOT_FOUND"));
    }

    @Test
    void confirmingPlanningDraftCreatesPathAndAttachesItToJourney() throws Exception {
        var learner = learnerRepository.findCurrent().orElseGet(() -> learnerRepository.save(
                Learner.create("Planning tester", "TypeScript developer", CREATED_AT)));
        var selected = journeyRepository.selectCurrent(
                journeyRepository.save(Journey.create(learner.id(), "Confirm a TypeScript plan", CREATED_AT)).id(),
                learner.id());
        var confirmUrl = new URL(bootstrapUrl, "/api/journeys/" + selected.id() + "/confirm-plan");

        var response = HTTP.send(
                HttpRequest.newBuilder(confirmUrl.toURI())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"plan\":" + jsonString(PLAN) + "}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
        var linked = journeyRepository.findById(selected.id()).orElseThrow();
        assertNotNull(linked.learningJourneyId());
        assertTrue(response.body().contains("\"learningJourneyId\":" + linked.learningJourneyId()));
        var learningJourney = learningJourneyRepository.findById(linked.learningJourneyId()).orElseThrow();
        assertTrue(learningJourney.learnUnits().getFirst().content().contains("## Concept"));
        assertTrue(learningJourney.learnUnits().getFirst().content().contains("## Example"));
        assertEquals(2, learningJourney.learnUnits().size());
        assertEquals("variables", learningJourney.currentItem().learnUnitCode());
    }

    @Test
    void confirmedPathCompletesAndAdvancesAfterPracticeEvidence() throws Exception {
        var learner = learnerRepository.findCurrent().orElseGet(() -> learnerRepository.save(
                Learner.create("Progress tester", "TypeScript developer", CREATED_AT)));
        var selected = journeyRepository.selectCurrent(
                journeyRepository.save(Journey.create(learner.id(), "Expose learning progress", CREATED_AT)).id(),
                learner.id());
        var confirmUrl = new URL(bootstrapUrl, "/api/journeys/" + selected.id() + "/confirm-plan");
        HTTP.send(
                HttpRequest.newBuilder(confirmUrl.toURI())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"plan\":" + jsonString(PLAN) + "}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        var confirmed = journeyRepository.findById(selected.id()).orElseThrow();
        var learningBeforePractice = learningJourneyRepository
                .findById(confirmed.learningJourneyId()).orElseThrow();
        learningJourneyRepository.save(
                learningBeforePractice.recordPracticeVerified("variables", Instant.now().plusSeconds(1)));

        var learningUrl = new URL(bootstrapUrl, "/api/journeys/" + selected.id() + "/learning");
        var progress = HTTP.send(
                HttpRequest.newBuilder(learningUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_OK, progress.statusCode());
        assertTrue(progress.body().contains("\"currentLearnUnitCode\":\"functions\""), progress.body());
        assertTrue(progress.body().contains("\"completedCount\":1"), progress.body());
    }

    @Test
    void bootstrapInitializesLearningWorkspaceAndFileApiRoundTrip() throws Exception {
        var learner = learnerRepository.findCurrent()
                .orElseGet(() -> learnerRepository.save(
                        Learner.create("Workspace tester", "TypeScript developer", CREATED_AT)));
        var journey = journeyRepository.selectCurrent(
                journeyRepository.save(Journey.create(learner.id(), "Build a TypeScript app", CREATED_AT)).id(),
                learner.id());
        var learningJourney = learningJourneyRepository.save(learningJourney(learner.id()));
        journeyRepository.attachLearningJourney(journey.id(), learningJourney.id(), learner.id());

        var bootstrap = HTTP.send(
                HttpRequest.newBuilder(bootstrapUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_OK, bootstrap.statusCode());
        assertTrue(bootstrap.body().contains("\"reference\":\"learning:" + journey.id() + "\""));

        var listUrl = new URL(bootstrapUrl,
                "/api/journeys/" + journey.id() + "/workspace/files");
        var files = HTTP.send(
                HttpRequest.newBuilder(listUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_OK, files.statusCode());
        assertTrue(files.body().contains("src/index.ts"));

        var fileUrl = new URL(bootstrapUrl,
                "/api/journeys/" + journey.id() + "/workspace/files/src/index.ts");
        var write = HTTP.send(
                HttpRequest.newBuilder(fileUrl.toURI())
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"content\":\"export const answer = 42;\"}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_OK, write.statusCode());

        var read = HTTP.send(
                HttpRequest.newBuilder(fileUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpURLConnection.HTTP_OK, read.statusCode());
        assertTrue(read.body().contains("export const answer = 42;"));
    }

    private LearningJourney learningJourney(long learnerId) {
        var chapter = Chapter.create("basics", "Basics", 0);
        var unit = LearnUnit.create(
                "variables", "Variables", "Use values", "Variables content", 0, "basics", Set.of());
        return LearningJourney.create(
                learnerId, "typescript", "TypeScript Journey",
                List.of(chapter), List.of(unit), CREATED_AT);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }
}
