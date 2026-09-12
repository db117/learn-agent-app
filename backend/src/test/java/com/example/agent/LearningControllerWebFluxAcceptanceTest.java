package com.example.agent;

import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.example.agent.learning.journey.JourneyDraftEvent;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "app.data-dir=target/webflux-learning-acceptance-data-v1",
                "app.database=target/webflux-learning-acceptance-data-v1/learning.db",
                "server.address=127.0.0.1",
                "server.port=18080",
                "app.openai.api-key=test-key",
                "app.openai.base-url=http://localhost"
        })
@Import(AgentBackendApplicationTest.TestCurriculumConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LearningControllerWebFluxAcceptanceTest {

    @Autowired
    private LearningJourneyService journeys;
    @Autowired
    private ProgressService progress;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:18080").build();
    }

    @Test
    void drivesTheProgressiveLearningLoopThroughTheWebFluxBoundary() {
        JsonNode draft = post("/api/learning/journey-drafts", Map.of(
                "languageCode", "typescript",
                "goal", "HTTP acceptance",
                "primaryLanguage", "Java",
                "experienceYears", 2,
                "selfDescription", "beginner",
                "learningGoal", "learn the core TypeScript path"));
        String runId = draft.at("/runId").asText();
        List<JourneyDraftEvent> outlineEvents = streamDraftEvents(runId, "outline_ready");
        assertEquals("outline_ready", outlineEvents.get(outlineEvents.size() - 1).eventType());
        post("/api/learning/journey-drafts/" + runId + "/confirm");
        JourneyDraftEvent confirmed = streamDraftEvents(runId, "confirmed").stream()
                .filter(event -> event.eventType().equals("confirmed"))
                .findFirst()
                .orElseThrow();
        String base = "/api/learning/journeys/" + confirmed.journeyId();

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
        JsonNode example = post(base + "/learn-units/" + firstCode + "/phase/EXPLANATION/advance");
        assertEquals("EXAMPLE", example.at("/pathItem/learningPhase").asText());
        JsonNode guided = post(base + "/learn-units/" + firstCode + "/phase/EXAMPLE/skip");
        assertEquals("GUIDED_PRACTICE", guided.at("/pathItem/learningPhase").asText());
        assertTrue(guided.at("/pathItem/skippedPhases").toString().contains("EXAMPLE"));
        JsonNode feedback = post(base + "/learn-units/" + firstCode + "/guided-practice",
                Map.of("response", "const answer = 42;"));
        assertEquals(1, feedback.at("/pathItem/guidedPracticeEntries").size());
        assertEquals("const answer = 42;",
                feedback.at("/pathItem/guidedPracticeEntries/0/response").asText());
        JsonNode independent = post(base + "/learn-units/" + firstCode + "/phase/GUIDED_PRACTICE/advance");
        assertEquals("INDEPENDENT_CHECK", independent.at("/pathItem/learningPhase").asText());

        JsonNode assessment = post(base + "/learn-units/" + firstCode + "/assessment");
        String assessmentId = assessment.at("/assessment/id").asText();
        String questionId = assessment.at("/questions/0/id").asText();
        assertFalse(assessment.at("/questions/0/configJson").asText().contains("correctOptionIds"));
        JsonNode assessmentStarted = post("/api/learning/assessments/" + assessmentId + "/start");
        String firstAttemptId = assessmentStarted.at("/openAttempt/id").asText();
        answer(assessmentId, questionId, "B");
        assertEquals("[\"B\"]",
                get("/api/learning/assessments/" + assessmentId)
                        .at("/questionAttempts/0/selectedOptionIdsJson").asText());
        JsonNode failed = post("/api/learning/assessments/" + assessmentId + "/submit");
        assertFalse(failed.at("/passed").asBoolean());
        assertEquals(1, get("/api/learning/assessments/" + assessmentId).at("/attempts").size());

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
        assertEquals(2, get("/api/learning/assessments/" + assessmentId).at("/attempts").size());

        detail = get(base);
        assertEquals("COMPLETED", detail.at("/path/0/status").asText());
        String secondCode = detail.at("/path/1/learnUnitCode").asText();
        post(base + "/learn-units/" + secondCode + "/skip");
        detail = get(base);
        assertEquals("SKIPPED", detail.at("/path/1/status").asText());
        String thirdCode = detail.at("/path/2/learnUnitCode").asText();
        post(base + "/learn-units/" + thirdCode + "/skip");
        detail = get(base);
        assertEquals("SKIPPED", detail.at("/path/2/status").asText());
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
        assertTrue(get(base).at("/path/0/needsReview").asBoolean());

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

    @Test
    void completedLearnUnitReviewGeneratesMissingContentWithoutChangingCompletion() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "completed review", "Java", 2,
                "beginner", "review a completed unit");
        String code = learningPathCode(journey.id());
        progress.recordDiagnosticResult(journey.id(), code, new AssessmentScore(100, 100, 100, true, false), true);

        JsonNode before = get("/api/learning/journeys/" + journey.id() + "/learn-units/" + code);
        assertEquals("COMPLETED", before.at("/pathItem/status").asText());
        assertTrue(before.at("/learnUnit/lessonIntro").asText().isBlank());
        String passedAt = before.at("/pathItem/passedAt").asText();
        String passReason = before.at("/pathItem/passReason").asText();
        int attemptCount = before.at("/pathItem/attemptCount").asInt();

        JsonNode reviewed = post("/api/learning/journeys/" + journey.id() + "/learn-units/" + code + "/review");
        assertEquals("COMPLETED", reviewed.at("/pathItem/status").asText());
        assertEquals(passedAt, reviewed.at("/pathItem/passedAt").asText());
        assertEquals(passReason, reviewed.at("/pathItem/passReason").asText());
        assertEquals(attemptCount, reviewed.at("/pathItem/attemptCount").asInt());
        assertFalse(reviewed.at("/learnUnit/lessonIntro").asText().isBlank());

        JsonNode practice = post("/api/learning/journeys/" + journey.id() + "/learn-units/" + code + "/practice");
        String assessmentId = practice.at("/assessment/id").asText();
        String questionId = practice.at("/questions/0/id").asText();
        post("/api/learning/assessments/" + assessmentId + "/start");
        answer(assessmentId, questionId, "B");
        assertFalse(post("/api/learning/assessments/" + assessmentId + "/submit").at("/passed").asBoolean());

        JsonNode afterPractice = get("/api/learning/journeys/" + journey.id() + "/learn-units/" + code);
        assertEquals("COMPLETED", afterPractice.at("/pathItem/status").asText());
        assertEquals(passedAt, afterPractice.at("/pathItem/passedAt").asText());
        assertEquals(passReason, afterPractice.at("/pathItem/passReason").asText());
        assertEquals(attemptCount + 1, afterPractice.at("/pathItem/attemptCount").asInt());
        assertEquals(1, afterPractice.at("/attempts").size());
    }

    private String learningPathCode(String journeyId) {
        return get("/api/learning/journeys/" + journeyId).at("/path/0/learnUnitCode").asText();
    }

    private List<JourneyDraftEvent> streamDraftEvents(String runId, String terminalEventType) {
        return Objects.requireNonNull(client.get()
                .uri("/api/learning/journey-drafts/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<JourneyDraftEvent>>() {})
                .getResponseBody()
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .takeUntil(event -> event.eventType().equals(terminalEventType))
                .collectList()
                .block(Duration.ofSeconds(5)));
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
