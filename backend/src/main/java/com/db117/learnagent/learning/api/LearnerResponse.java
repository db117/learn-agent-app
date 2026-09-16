package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.domain.Learner;

public record LearnerResponse(
        long id,
        String displayName,
        String backgroundSummary) {
    public static LearnerResponse from(Learner learner) {
        return new LearnerResponse(learner.id(), learner.displayName(), learner.backgroundSummary());
    }
}
