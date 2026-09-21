package com.db117.learnagent.practice;

import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @TestHTTPResource("/api/bootstrap")
    URL bootstrapUrl;

    @Test
    void completesARealPracticeFailureFixAndAdvancesFlow() throws Exception {
        var learner = put("/api/learner", "{\"backgroundSummary\":\"TypeScript learner\"}", 200);
        assertTrue(learner.body().contains("\"id\":"));

        var journey = post("/api/journeys", "{\"goalDescription\":\"Practice TypeScript\"}", 200);
        long journeyId = jsonLong(journey.body(), "id");
        post("/api/journeys/" + journeyId + "/confirm-plan",
                "{\"plan\":" + jsonString(PLAN) + "}", 200);
        long learningJourneyId = jsonLong(
                get("/api/bootstrap", 200).body(), "learningJourneyId");
        var learning = get("/api/journeys/" + journeyId + "/learning", 200);
        assertTrue(learning.body().contains("\"currentLearnUnitContent\":"));
        assertTrue(learning.body().contains("## Concept"));
        assertTrue(learning.body().contains("## Example"));

        var files = get("/api/journeys/" + journeyId + "/workspace/files", 200);
        assertTrue(files.body().contains("src/index.ts"));

        var initialVerification = post("/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(initialVerification.body().contains("\"verified\":false"));
        long taskId = jsonLong(initialVerification.body(), "taskId");
        assertEquals("模型生成的 Practice：完成「变量与类型」对应的练习。",
                practiceTasks.findById(taskId).orElseThrow().description());

        putFile("/api/journeys/" + journeyId + "/workspace/files/src/index.ts",
                "export const answer: number = \"broken\";", 200);
        putFile("/api/journeys/" + journeyId + "/workspace/files/src/index.test.mjs",
                "import assert from \"node:assert/strict\";\n"
                        + "import { answer } from \"./index.ts\";\n"
                        + "it(\"returns the answer\", () => assert.equal(answer, 42));\n", 200);

        var failedCompile = post("/api/journeys/" + journeyId + "/practice/compile", null, 200);
        assertTrue(failedCompile.body().contains("\"success\":false"));
        assertTrue(failedCompile.body().contains("TS2322"));

        var failedTests = post("/api/journeys/" + journeyId + "/practice/tests", null, 200);
        assertTrue(failedTests.body().contains("\"success\":false"));
        assertTrue(failedTests.body().contains("\"passed\":false"));

        var failedVerification = post(
                "/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(failedVerification.body().contains("\"verified\":false"));
        var taskAfterFailure = practiceTasks.findById(taskId).orElseThrow();
        assertEquals(2, taskAfterFailure.attempts().size());
        assertEquals("OPEN", taskAfterFailure.status().name());
        assertFalse(taskAfterFailure.attempts().getLast().evidence().compilePassed());
        assertFalse(taskAfterFailure.attempts().getLast().evidence().testsPassed());
        assertNull(taskAfterFailure.attempts().getLast().evidence().verifiedAt());

        putFile("/api/journeys/" + journeyId + "/workspace/files/src/index.ts",
                "export const answer: number = 42;", 200);

        var passedCompile = post("/api/journeys/" + journeyId + "/practice/compile", null, 200);
        assertTrue(passedCompile.body().contains("\"success\":true"));
        var passedTests = post("/api/journeys/" + journeyId + "/practice/tests", null, 200);
        assertTrue(passedTests.body().contains("\"success\":true"));
        assertTrue(passedTests.body().contains("\"passed\":true"));
        assertTrue(passedTests.body().contains("\"testCount\":1"));

        var verified = post("/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(verified.body().contains("\"verified\":true"));
        assertTrue(verified.body().contains("\"status\":\"VERIFIED\""));
        assertSafeVerifyResponse(verified.body());
        var taskAfterVerification = practiceTasks.findById(taskId).orElseThrow();
        assertEquals(3, taskAfterVerification.attempts().size());
        var evidence = taskAfterVerification.attempts().getLast().evidence();
        assertTrue(evidence.compilePassed());
        assertTrue(evidence.testsPassed());
        assertEquals(1, evidence.testCount());
        assertNotNull(evidence.verifiedAt());

        var secondVerification = post("/api/journeys/" + journeyId + "/practice/verify", null, 200);
        assertTrue(secondVerification.body().contains("\"verified\":true"));
        assertSafeVerifyResponse(secondVerification.body());
        long secondTaskId = jsonLong(secondVerification.body(), "taskId");
        var secondTask = practiceTasks.findById(secondTaskId).orElseThrow();
        assertEquals("CODE", secondTask.type());
        assertEquals("VERIFIED", secondTask.status().name());
        assertEquals(1, secondTask.attempts().size());

        var persisted = learningJourneys.findById(learningJourneyId).orElseThrow();
        assertEquals("COMPLETED", persisted.status().name());
        assertEquals(LearningPathItemStatus.COMPLETED, persisted.pathItems().getFirst().status());
        assertEquals(LearningPathItemStatus.COMPLETED, persisted.pathItems().getLast().status());
        assertTrue(persisted.pathItems().getLast().practiceVerified());
        assertEquals("PRACTICE_EVIDENCE", persisted.pathItems().getLast().passReason());
        assertNull(persisted.currentItem());
    }

    private HttpResponse<String> get(String path, int expectedStatus) throws Exception {
        return send(HttpRequest.newBuilder(url(path).toURI()).GET().build(), expectedStatus);
    }

    private HttpResponse<String> post(String path, String body, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(url(path).toURI())
                .header("Content-Type", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, expectedStatus);
    }

    private HttpResponse<String> put(String path, String body, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(url(path).toURI())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, expectedStatus);
    }

    private HttpResponse<String> putFile(String path, String content, int expectedStatus) throws Exception {
        return put(path, "{\"content\":" + jsonString(content) + "}", expectedStatus);
    }

    private HttpResponse<String> send(HttpRequest request, int expectedStatus) throws Exception {
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
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

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    /** 锁定 Practice REST 对 UI 的安全摘要契约；不把运行时内部数据带出边界。 */
    private static void assertSafeVerifyResponse(String body) {
        var fields = Set.of(
                "taskId", "status", "verified", "compilePassed", "testsPassed", "testCount",
                "submittedFiles", "verifiedAt", "learningJourneyStatus", "currentLearnUnitCode", "advanced");
        for (var field : fields) {
            assertTrue(body.contains("\"" + field + "\":"), () -> "Missing VerifyResponse field: " + field);
        }
        for (var forbidden : List.of(
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
