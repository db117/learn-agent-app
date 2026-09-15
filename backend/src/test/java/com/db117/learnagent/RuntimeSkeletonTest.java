package com.db117.learnagent;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RuntimeSkeletonTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestHTTPResource("/health")
    URL healthUrl;

    @TestHTTPResource("/api/tutor/sessions")
    URL tutorSessionsUrl;

    @TestHTTPResource("/api/tutor/sessions/missing/messages")
    URL missingSessionMessagesUrl;

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
}
