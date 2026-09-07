package com.example.agent.api;

import java.time.Instant;

public record HealthResponse(
        String status, String sqlite, String adk, String llm, Instant checkedAt) {
}
