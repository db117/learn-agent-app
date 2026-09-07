package com.example.agent.persistence;

import java.time.Instant;

public record RunRecord(
    String id,
    String sessionId,
    String status,
    String errorMessage,
    Instant startedAt,
    Instant completedAt) {}
