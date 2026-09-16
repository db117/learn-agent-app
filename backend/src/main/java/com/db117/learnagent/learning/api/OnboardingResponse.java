package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.application.JourneyApplicationService;

import java.util.List;

public record OnboardingResponse(
        LearnerResponse learner,
        List<JourneyResponse> journeys) {
    public OnboardingResponse {
        journeys = List.copyOf(journeys == null ? List.of() : journeys);
    }

    public static OnboardingResponse from(JourneyApplicationService.OnboardingSnapshot snapshot) {
        return new OnboardingResponse(
                snapshot.learner() == null ? null : LearnerResponse.from(snapshot.learner()),
                snapshot.journeys().stream().map(JourneyResponse::from).toList());
    }
}
