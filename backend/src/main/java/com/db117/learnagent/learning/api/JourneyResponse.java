package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.domain.Journey;

public record JourneyResponse(
        long id,
        String goalDescription,
        String status,
        boolean current,
        Long learningJourneyId) {
    public static JourneyResponse from(Journey journey) {
        return new JourneyResponse(
                journey.id(),
                journey.goalDescription(),
                journey.status().name(),
                journey.current(),
                journey.learningJourneyId());
    }
}
