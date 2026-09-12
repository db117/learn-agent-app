package com.example.agent;

import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.data-dir=target/webflux-learning-acceptance-data-v1",
                "app.database=target/webflux-learning-acceptance-data-v1/learning.db",
                "app.openai.api-key=test-key",
                "app.openai.base-url=http://localhost"
        })
@Import(AgentBackendApplicationTest.TestCurriculumConfiguration.class)
class LearningControllerWebFluxAcceptanceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LearningJourneyService journeys;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void drivesTheProgressiveLearningLoopThroughTheWebFluxBoundary() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "HTTP acceptance", "Java", 2,
                "beginner", "learn the core TypeScript path");
        String base = "/api/learning/journeys/" + journey.id();

        JsonNode detail = get(base);
        assertEquals(1, detail.at("/chapters").size());
        assertEquals(3, detail.at("/chapters/0/learnUnits").size());
        String chapterCode = detail.at("/chapters/0/chapter/code").asText();
        String firstCode = detail.at("/path/0/learnUnitCode").asText();

        JsonNode outline = get(base + "/learn-units/" + firstCode);
        assertTrue(outline.at("/learnUnit/lessonIntro").asText().isBlank());

        JsonNode started = post(base + "/learn-units/" + firstCode + "/start");
        assertFalse(started.at("/learnUnit/lessonIntro").asText().isBlank());
        assertEquals("EXPLANATION", started.at("/pathItem/learningPhase").asText());
        post(base + "/learn-units/" + firstCode + "/phase/EXPLANATION/advance");
        JsonNode guided = post(base + "/learn-units/" + firstCode + "/phase/EXAMPLE/skip");
        assertEquals("GUIDED_PRACTICE", guided.at("/pathItem/learningPhase").asText());
        JsonNode feedback = post(base + "/learn-units/" + firstCode + "/guided-practice",
                Map.of("response", "const answer = 42;"));
        assertEquals(1, feedback.at("/pathItem/guidedPracticeEntries").size());
        JsonNode independent = post(base + "/learn-units/" + firstCode + "/phase/GUIDED_PRACTICE/advance");
        assertEquals("INDEPENDENT_CHECK", independent.at("/pathItem/learningPhase").asText());

        JsonNode assessment = post(base + "/learn-units/" + firstCode + "/assessment");
        String assessmentId = assessment.at("/assessment/id").asText();
        String questionId = assessment.at("/questions/0/id").asText();
        assertFalse(assessment.at("/questions/0/configJson").asText().contains("correctOptionIds"));
        JsonNode assessmentStarted = post("/api/learning/assessments/" + assessmentId + "/start");
        String firstAttemptId = assessmentStarted.at("/openAttempt/id").asText();
        answer(assessmentId, questionId, "B");
        JsonNode failed = post("/api/learning/assessments/" + assessmentId + "/submit");
        assertFalse(failed.at("/passed").asBoolean());

        detail = get(base);
        assertEquals("CURRENT", detail.at("/path/0/status").asText());
        assertTrue(detail.at("/path/0/needsReview").asBoolean());
        JsonNode retry = post(base + "/learn-units/" + firstCode + "/retry");
        assertEquals(assessmentId, retry.at("/assessment/id").asText());
        assertEquals(questionId, retry.at("/questions/0/id").asText());
        assertNotEquals(firstAttemptId, retry.at("/openAttempt/id").asText());
        answer(assessmentId, questionId, "A");
        JsonNode passed = post("/api/learning/assessments/" + assessmentId + "/submit");
        assertTrue(passed.at("/passed").asBoolean());

        detail = get(base);
        assertEquals("COMPLETED", detail.at("/path/0/status").asText());
        String secondCode = detail.at("/path/1/learnUnitCode").asText();
        post(base + "/learn-units/" + secondCode + "/skip");
        detail = get(base);
        String thirdCode = detail.at("/path/2/learnUnitCode").asText();
        post(base + "/learn-units/" + thirdCode + "/skip");
        detail = get(base);
        for (JsonNode item : detail.at("/path")) {
            assertNotEquals("CURRENT", item.at("/status").asText());
        }
        assertTrue(detail.at("/chapters/0/synthesisAvailable").asBoolean());

        JsonNode synthesis = post(base + "/chapters/" + chapterCode + "/synthesis");
        String synthesisId = synthesis.at("/assessment/id").asText();
        JsonNode synthesisStarted = post("/api/learning/assessments/" + synthesisId + "/start");
        List<String> synthesisQuestionIds = questionIds(synthesisStarted);
        for (String id : synthesisQuestionIds) answer(synthesisId, id, "B");
        JsonNode failedSynthesis = post("/api/learning/assessments/" + synthesisId + "/submit");
        assertFalse(failedSynthesis.at("/passed").asBoolean());
        assertEquals(firstCode, failedSynthesis.at("/reviewLearnUnitCode").asText());

        JsonNode synthesisRetry = post(base + "/chapters/" + chapterCode + "/synthesis/retry");
        assertEquals(synthesisId, synthesisRetry.at("/assessment/id").asText());
        assertEquals(synthesisQuestionIds, questionIds(synthesisRetry));
        for (String id : synthesisQuestionIds) answer(synthesisId, id, "A");
        JsonNode passedSynthesis = post("/api/learning/assessments/" + synthesisId + "/submit");
        assertTrue(passedSynthesis.at("/passed").asBoolean());
        assertFalse(passedSynthesis.at("/chapterCompleted").asBoolean());
        detail = get(base);
        assertTrue(detail.at("/chapters/0/synthesisCompleted").asBoolean());
        assertEquals("ACTIVE", detail.at("/journey/status").asText());
        assertTrue(detail.at("/chapters/0/unresolvedCount").asInt() > 0);
    }

    private List<String> questionIds(JsonNode response) {
        List<String> ids = new java.util.ArrayList<>();
        response.at("/questions").forEach(question -> ids.add(question.at("/id").asText()));
        return ids;
    }

    private void answer(String assessmentId, String questionId, String option) {
        post("/api/learning/assessments/" + assessmentId + "/answers",
                Map.of("questionId", questionId, "selectedOptionIds", List.of(option), "submittedCode", ""));
    }

    private JsonNode get(String uri) {
        return body(client.get().uri(uri).exchange());
    }

    private JsonNode post(String uri) {
        return body(client.post().uri(uri).exchange());
    }

    private JsonNode post(String uri, Object value) {
        return body(client.post().uri(uri).bodyValue(value).exchange());
    }

    private JsonNode body(WebTestClient.ResponseSpec response) {
        String raw = response.expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(raw);
        try {
            return objectMapper.readTree(raw);
        } catch (Exception error) {
            throw new AssertionError("Response was not valid JSON", error);
        }
    }
}
