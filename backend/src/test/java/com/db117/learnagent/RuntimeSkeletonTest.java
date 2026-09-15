package com.db117.learnagent;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    @TestHTTPResource("/events")
    URL eventsUrl;

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
    void eventsAreStreamedAsServerSentEvents() throws Exception {
        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder(eventsUrl.toURI())
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertTrue(reader.readLine().contains("runtime.ready"));
        }
    }
}
