package com.example.agent.api;

import com.example.agent.persistence.MessageRecord;

import java.time.Instant;
import java.util.List;

public record SessionDetailResponse(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<MessageRecord> messages) {
}
