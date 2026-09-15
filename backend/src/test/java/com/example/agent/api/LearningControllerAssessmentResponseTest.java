package com.example.agent.api;

import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentService;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionFixtures;
import com.example.agent.learning.assessment.QuestionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class LearningControllerAssessmentResponseTest {

    @Test
    void assessmentResponseRedactsAnswersAndEvaluatorDataWhileOpen() {
        Question question = new Question(
                "question", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                QuestionFixtures.singleChoiceConfig(), null, null, null, List.of(), false);
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        AssessmentAttempt open = new AssessmentAttempt(
                "attempt", "assessment", "journey", "learnUnit-a", 1,
                null, null, null, null, Instant.EPOCH, null);
        QuestionAttempt raw = new QuestionAttempt(
                question.id(), open.id(), "{}", 20, 20, "Correct.", true,
                null, "{\"private\":true}", List.of("A"));

        LearningController.AssessmentResponse response = LearningController.AssessmentResponse.from(
                new AssessmentService.AssessmentState(assessment, List.of(question), open, List.of(), List.of(raw)));

        assertEquals(List.of("A"), response.questions().get(0).config().options().stream()
                .map(option -> option.id()).toList());
        assertFalse(response.questions().get(0).config().multiple());
        QuestionAttempt publicAttempt = response.questionAttempts().get(0);
        assertEquals(List.of("A"), publicAttempt.selectedOptionIds());
        assertNull(publicAttempt.score());
        assertNull(publicAttempt.correct());
        assertNull(publicAttempt.feedback());
        assertNull(publicAttempt.evaluationJson());
    }
}
