package com.example.agent.persistence;

import java.time.Instant;

public record SessionRecord(
    String id, String userId, String title, Instant createdAt, Instant updatedAt) {}
