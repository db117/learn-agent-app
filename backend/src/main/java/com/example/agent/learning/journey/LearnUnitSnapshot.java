package com.example.agent.learning.journey;

import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.LearningPathItem;

import java.util.List;

/** LearnUnit 详情读取结果；只组合 Journey 内的持久化事实。 */
public record LearnUnitSnapshot(
        String journeyId,
        LearnUnit learnUnit,
        LearningPathItem pathItem,
        List<AssessmentAttempt> attempts,
        List<QuestionAttempt> questionAttempts) {

    public LearnUnitSnapshot {
        attempts = List.copyOf(attempts);
        questionAttempts = List.copyOf(questionAttempts);
    }
}
