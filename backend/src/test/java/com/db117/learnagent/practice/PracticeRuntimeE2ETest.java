package com.db117.learnagent.practice;

import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
import com.db117.learnagent.practice.domain.PracticeAssessment;
import com.db117.learnagent.practice.domain.PracticeAssessmentRepository;
import com.db117.learnagent.practice.domain.PracticeAssessmentVerdict;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PracticeRuntimeE2ETest.IsolatedPracticeProfile.class)
class PracticeRuntimeE2ETest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
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
                      "objective": "能够声明变量并理解基本类型"
                    },
                    {
                      "code": "functions",
                      "title": "函数",
                      "objective": "能够声明带类型的函数"
                    },
                    {
                      "code": "async",
                      "title": "异步函数",
                      "objective": "能够理解 Promise 与 async/await"
                    }
                  ]
                }
              ]
            }
            """;

    @Inject
    PracticeTaskRepository practiceTasks;

    @Inject
    LearningJourneyRepository learningJourneys;

    @Inject
    PracticeAssessmentRepository assessments;

    @TestHTTPResource("/api/bootstrap")
    URL bootstrapUrl;

    @Test
    void recordsRealPracticeEvidenceThenWaitsForConfirmedTutorAssessment() throws Exception {
        HttpResponse<String> learner = put("/api/learner", "{\"backgroundSummary\":\"TypeScript learner\"}", 200);
        assertTrue(learner.body().contains("\"id\":"));

        HttpResponse<String> journey = post("/api/journeys", "{\"goalDescription\":\"Practice TypeScript\"}", 200);
        long journeyId = jsonLong(journey.body(), "id");
        post("/api/journeys/" + journeyId + "/confirm-plan",
                "{\"plan\":" + jsonString(PLAN) + "}", 200);
        long learningJourneyId = jsonLong(
                get("/api/bootstrap", 200).body(), "learningJourneyId");
        HttpResponse<String> learning = get("/api/journeys/" + journeyId + "/learning", 200);
        assertTrue(learning.body().contains("\"currentLearnUnitContent\":"));
        assertTrue(learning.body().contains("\"currentLearnUnitContent\":\"\""));

        HttpResponse<String> files = get("/api/journeys/" + journeyId + "/workspace/files", 200);
        assertTrue(files.body().contains("src/index.ts"));

        HttpResponse<String> initialVerification = post("/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(initialVerification.body().contains("\"verified\":false"));
        long taskId = jsonLong(initialVerification.body(), "taskId");
        assertEquals("能够声明变量并理解基本类型",
                practiceTasks.findById(taskId).orElseThrow().description());

        putFile("/api/journeys/" + journeyId + "/workspace/files/src/index.ts",
                "export const answer: number = \"broken\";", 200);
        putFile("/api/journeys/" + journeyId + "/workspace/files/src/index.test.mjs",
                "import assert from \"node:assert/strict\";\n"
                        + "import { answer } from \"./index.ts\";\n"
                        + "it(\"returns the answer\", () => assert.equal(answer, 42));\n", 200);

        HttpResponse<String> failedCompile = post("/api/journeys/" + journeyId + "/practice/compile", null, 200);
        assertTrue(failedCompile.body().contains("\"success\":false"));
        assertTrue(failedCompile.body().contains("TS2322"));

        HttpResponse<String> failedTests = post("/api/journeys/" + journeyId + "/practice/tests", null, 200);
        assertTrue(failedTests.body().contains("\"success\":false"));
        assertTrue(failedTests.body().contains("\"passed\":false"));

        HttpResponse<String> failedVerification = post(
                "/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(failedVerification.body().contains("\"verified\":false"));
        com.db117.learnagent.practice.domain.PracticeTask taskAfterFailure = practiceTasks.findById(taskId).orElseThrow();
        assertEquals(2, taskAfterFailure.attempts().size());
        assertEquals("OPEN", taskAfterFailure.status().name());
        assertFalse(taskAfterFailure.attempts().getLast().evidence().compilePassed());
        assertFalse(taskAfterFailure.attempts().getLast().evidence().testsPassed());
        assertNull(taskAfterFailure.attempts().getLast().evidence().verifiedAt());

        putFile("/api/journeys/" + journeyId + "/workspace/files/src/index.ts",
                "export const answer: number = 42;", 200);

        HttpResponse<String> passedCompile = post("/api/journeys/" + journeyId + "/practice/compile", null, 200);
        assertTrue(passedCompile.body().contains("\"success\":true"));
        HttpResponse<String> passedTests = post("/api/journeys/" + journeyId + "/practice/tests", null, 200);
        assertTrue(passedTests.body().contains("\"success\":true"));
        assertTrue(passedTests.body().contains("\"passed\":true"));
        assertTrue(passedTests.body().contains("\"testCount\":1"));

        HttpResponse<String> verified = post("/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(verified.body().contains("\"verified\":true"));
        assertTrue(verified.body().contains("\"status\":\"VERIFIED\""));
        assertSafeVerifyResponse(verified.body());
        com.db117.learnagent.practice.domain.PracticeTask taskAfterVerification = practiceTasks.findById(taskId).orElseThrow();
        assertEquals(3, taskAfterVerification.attempts().size());
        com.db117.learnagent.practice.domain.PracticeEvidence evidence = taskAfterVerification.attempts().getLast().evidence();
        assertTrue(evidence.compilePassed());
        assertTrue(evidence.testsPassed());
        assertEquals(1, evidence.testCount());
        assertNotNull(evidence.verifiedAt());

        com.db117.learnagent.learning.domain.LearningJourney persisted =
                learningJourneys.findById(learningJourneyId).orElseThrow();
        assertEquals("ACTIVE", persisted.status().name());
        assertEquals("variables", persisted.currentItem().learnUnitCode());
        assertFalse(persisted.pathItems().getFirst().practiceVerified());

        com.db117.learnagent.practice.domain.PracticeTask checkedTask = practiceTasks.findById(taskId).orElseThrow();
        com.db117.learnagent.practice.domain.PracticeEvidence checkedEvidence =
                checkedTask.attempts().getLast().evidence();
        com.db117.learnagent.practice.domain.PracticeAssessment assessment = assessments.save(
                PracticeAssessment.create(
                        learningJourneyId,
                        persisted.learnUnit("variables").id(),
                        taskId,
                        checkedTask.attempts().getLast().id(),
                        PracticeAssessmentVerdict.READY,
                        "代码检查通过；对话中能说明变量类型的用途。",
                        checkedEvidence.workspaceDigest(),
                        java.time.Instant.now()));

        HttpResponse<String> pendingAssessment = get(
                "/api/journeys/" + journeyId + "/practice/assessment", 200);
        assertTrue(pendingAssessment.body().contains("\"verdict\":\"READY\""));
        assertTrue(pendingAssessment.body().contains("\"stale\":false"));
        HttpResponse<String> accepted = post(
                "/api/journeys/" + journeyId + "/practice/assessments/" + assessment.id() + "/accept", null, 200);
        assertTrue(accepted.body().contains("\"currentLearnUnitCode\":\"functions\""));

        HttpResponse<String> confirmedRoute = post(
                "/api/journeys/" + journeyId + "/learning/route/confirm",
                "{\"plan\":" + jsonString("""
                        {"reason":"先学异步基础，再补充循环练习。","chapters":[
                          {"code":"basics","title":"基础","units":[
                            {"code":"async","title":"异步函数","objective":"理解 async/await"}]},
                          {"code":"review","title":"复习","units":[
                            {"code":"loops","title":"循环","objective":"使用循环遍历集合"}]}
                        ]}
                        """) + "}",
                200);
        assertTrue(confirmedRoute.body().contains("\"currentLearnUnitCode\":\"async\""));

        com.db117.learnagent.learning.domain.LearningJourney replanned =
                learningJourneys.findById(learningJourneyId).orElseThrow();
        assertEquals(LearningPathItemStatus.COMPLETED, pathItem(replanned, "variables").status());
        assertEquals(assessment.id(), pathItem(replanned, "variables").assessmentId());
        assertTrue(pathItem(replanned, "variables").practiceVerified());
        assertEquals("AGENT_ASSESSMENT", pathItem(replanned, "variables").passReason());
        assertEquals(LearningPathItemStatus.SKIPPED, pathItem(replanned, "functions").status());
        assertEquals(LearningPathItemStatus.CURRENT, pathItem(replanned, "async").status());
        assertEquals(LearningPathItemStatus.PENDING, pathItem(replanned, "loops").status());
        assertNotNull(replanned.learnUnit("loops").id());
        assertEquals("variables", replanned.pathItems().getFirst().learnUnitCode());
    }

    private HttpResponse<String> get(String path, int expectedStatus) throws Exception {
        return send(HttpRequest.newBuilder(url(path).toURI()).GET().build(), expectedStatus);
    }

    private HttpResponse<String> post(String path, String body, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url(path).toURI())
                .header("Content-Type", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, expectedStatus);
    }

    private HttpResponse<String> put(String path, String body, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url(path).toURI())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, expectedStatus);
    }

    private HttpResponse<String> putFile(String path, String content, int expectedStatus) throws Exception {
        return put(path, "{\"content\":" + jsonString(content) + "}", expectedStatus);
    }

    private HttpResponse<String> send(HttpRequest request, int expectedStatus) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.uri() + "\n" + response.body());
        return response;
    }

    private URL url(String path) throws Exception {
        return new URL(bootstrapUrl, path);
    }

    private static long jsonLong(String body, String name) {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*(\\d+)").matcher(body);
        assertTrue(matcher.find(), () -> "Missing JSON number " + name + " in: " + body);
        return Long.parseLong(matcher.group(1));
    }

    private static com.db117.learnagent.learning.domain.LearningPathItem pathItem(
            com.db117.learnagent.learning.domain.LearningJourney journey,
            String learnUnitCode) {
        return journey.pathItems().stream()
                .filter(item -> item.learnUnitCode().equals(learnUnitCode))
                .findFirst()
                .orElseThrow();
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    /** 锁定 Practice REST 对 UI 的安全摘要契约；不把运行时内部数据带出边界。 */
    private static void assertSafeVerifyResponse(String body) {
        Set<String> fields = Set.of(
                "taskId", "assessmentAttemptId", "status", "verified", "compilePassed", "testsPassed", "testCount",
                "submittedFiles", "verifiedAt", "learningJourneyStatus", "currentLearnUnitCode", "advanced");
        for (String field : fields) {
            assertTrue(body.contains("\"" + field + "\":"), () -> "Missing VerifyResponse field: " + field);
        }
        for (String forbidden : List.of(
                "hostPath", "stdout", "stderr", "prompt", "answer", "secret", "reasoning", "practice.verified")) {
            assertFalse(body.contains("\"" + forbidden + "\":"),
                    () -> "Unsafe or out-of-band field leaked: " + forbidden);
        }
        assertFalse(body.contains("C:\\") || body.contains("F:\\") || body.contains("/tmp/"),
                "VerifyResponse must not contain a host path");
    }

    /** 测试专用临时数据目录，确保真实 Workspace 不会回退到仓库祖先的 node_modules。 */
    public static final class IsolatedPracticeProfile implements QuarkusTestProfile {
        private static final Path DATA_DIR = Path.of(
                System.getProperty("java.io.tmpdir"), "learn-agent-practice-e2e-" + UUID.randomUUID());

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("learn-agent.data-dir", DATA_DIR.toString());
        }
    }
}
