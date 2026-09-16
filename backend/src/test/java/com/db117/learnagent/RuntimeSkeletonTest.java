package com.db117.learnagent;

import com.db117.learnagent.learning.domain.Assessment;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RuntimeSkeletonTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

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
        var assessment = Assessment.create(
                "variables", 70, List.of(com.db117.learnagent.learning.domain.Question.singleChoice(
                        "choice", "Choose", List.of("yes", "no"), "yes")));
        return LearningJourney.create(
                learnerId, "typescript", "TypeScript Journey",
                List.of(chapter), List.of(unit), List.of(assessment), CREATED_AT);
    }
}
