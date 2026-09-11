package com.example.agent.learning.tutor;

import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.path.LearningPhase;
import com.example.agent.learning.persistence.LearningRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TutorContextServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final TutorContextService service = new TutorContextService(repository);

    @Test
    void suppliesNextStepAndReadOnlyLearningFactsToTutor() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "typescript", "goal", JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearnUnit current = learnUnit("unit-a", 1);
        LearnUnit next = learnUnit("unit-b", 2);
        LearningPathItem currentPath = new LearningPathItem(
                "path-a", "journey", "unit-a", 1, LearningPathItemStatus.CURRENT,
                72, 80, 2, null, Instant.EPOCH, null, null);
        LearningPathItem nextPath = new LearningPathItem(
                "path-b", "journey", "unit-b", 2, LearningPathItemStatus.PENDING);
        when(repository.findTutorSessionBySessionId("session"))
                .thenReturn(Optional.of(new LearningRepository.TutorSessionLink("journey", "unit-a", "session")));
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findProfile("journey")).thenReturn(Optional.of(
                new LearnerProfile("journey", "Java", 8, "backend developer", "learn TypeScript")));
        when(repository.findLearnUnit("unit-a")).thenReturn(Optional.of(current));
        when(repository.findLearnUnit("unit-b")).thenReturn(Optional.of(next));
        when(repository.findPathItem("journey", "unit-a")).thenReturn(Optional.of(currentPath));
        when(repository.listPath("journey")).thenReturn(List.of(currentPath, nextPath));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(current, next));
        when(repository.listQuestionAttemptsForLearnUnit("journey", "unit-a")).thenReturn(List.of(
                new QuestionAttempt("q", "attempt", "{}", 0, 10, "Review generics", false,
                        null, null, null)));

        TutorContext context = service.forSession("session");

        assertEquals("typescript", context.targetLanguage());
        assertEquals(current, context.currentLearnUnit());
        assertEquals(next, context.nextLearnUnit());
        assertEquals(72, context.mastery().currentScore());
        assertEquals(List.of("Review generics"), context.weakPoints());
        assertTrue(context.systemPrompt().contains("unit-b"));
        assertThrows(UnsupportedOperationException.class, () -> context.weakPoints().add("mutate"));
    }

    @Test
    void suppliesPhaseAndRemediationFactsWithoutGrantingTutorMutationRights() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "typescript", "goal", JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearnUnit current = learnUnit("unit-a", 1).withStructuredContent(
                "Explain generic constraints", 10, "intro", List.of("example"),
                "practice", List.of("hint"), "check");
        LearningPathItem currentPath = new LearningPathItem(
                "path-a", "journey", "unit-a", 1, LearningPathItemStatus.CURRENT,
                40, 40, 1, null, Instant.EPOCH, null, null, LearningPhase.INDEPENDENT_CHECK,
                List.of(), List.of(), true);
        when(repository.findTutorSessionBySessionId("review-session"))
                .thenReturn(Optional.of(new LearningRepository.TutorSessionLink("journey", "unit-a", "review-session")));
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(repository.findLearnUnit("unit-a")).thenReturn(Optional.of(current));
        when(repository.findPathItem("journey", "unit-a")).thenReturn(Optional.of(currentPath));
        when(repository.listPath("journey")).thenReturn(List.of(currentPath));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(current));
        when(repository.listQuestionAttemptsForLearnUnit("journey", "unit-a")).thenReturn(List.of());

        TutorContext context = service.forSession("review-session");

        assertEquals(LearningPhase.INDEPENDENT_CHECK, context.learningPhase());
        assertEquals("Explain generic constraints", context.failedAbility());
        assertTrue(context.remediationNeeded());
        assertTrue(context.systemPrompt().contains("remediation"));
    }

    private LearnUnit learnUnit(String code, int sequence) {
        return new LearnUnit(
                code, "typescript", code, "chapter", code, "description", sequence, List.of(), 80, null,
                true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
    }
}
