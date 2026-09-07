package com.example.agent.persistence;

import java.time.Instant;

public record MessageRecord(
        String id, String sessionId, String role, String content, Instant createdAt) {
}
