package com.example.agent.api;

import com.example.agent.persistence.SessionRecord;

import java.time.Instant;

public record SessionResponse(String id, String title, Instant createdAt, Instant updatedAt) {

    public static SessionResponse from(SessionRecord session) {
        return new SessionResponse(session.id(), session.title(), session.createdAt(), session.updatedAt());
    }
}
