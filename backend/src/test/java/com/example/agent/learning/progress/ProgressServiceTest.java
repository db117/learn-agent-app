package com.example.agent.learning.progress;

import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearnerLearnUnit;
import com.example.agent.learning.journey.LearnerLearnUnitStatus;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final ProgressService service = new ProgressService(repository, new DeterministicLearningPathPlanner());

    @BeforeEach
    void journeyExists() {
        when(repository.findJourney("journey")).thenReturn(Optional.of(new LearningJourney(
                "journey", "user", "typescript", "goal", JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH, "learnUnit-a")));
    }

    @Test
    void skipKeepsMasteryAndMovesToTheNextLearnUnit() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT);
        LearningPathItem next = item("learnUnit-b", 2, LearningPathItemStatus.PENDING);
        LearnerLearnUnit existing = new LearnerLearnUnit(
                "journey", "learnUnit-a", LearnerLearnUnitStatus.LEARNING, 72, 78, 2, null, Instant.EPOCH, null, null);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.findLearnerLearnUnit("journey", "learnUnit-a")).thenReturn(Optional.of(existing));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        service.skipLearnUnit("journey", "learnUnit-a");

        ArgumentCaptor<LearnerLearnUnit> learnerLearnUnit = ArgumentCaptor.forClass(LearnerLearnUnit.class);
        verify(repository).upsertLearnerLearnUnit(learnerLearnUnit.capture());
        assertEquals(LearnerLearnUnitStatus.SKIPPED, learnerLearnUnit.getValue().status());
        assertEquals(72, learnerLearnUnit.getValue().masteryScore());
        assertEquals(78, learnerLearnUnit.getValue().bestAssessmentScore());
        assertEquals(2, learnerLearnUnit.getValue().attemptCount());
        assertEquals(null, learnerLearnUnit.getValue().passReason());
        verify(repository).updatePathItem("journey", "learnUnit-a", LearningPathItemStatus.SKIPPED);
        verify(repository).updatePathItem("journey", "learnUnit-b", LearningPathItemStatus.CURRENT);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), eq("learnUnit-b"), any(Instant.class));
    }

    @Test
    void cannotSkipOrStartAPendingLearnUnit() {
        LearningPathItem pending = item("learnUnit-b", 2, LearningPathItemStatus.PENDING);
        when(repository.findPathItem("journey", "learnUnit-b")).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class, () -> service.skipLearnUnit("journey", "learnUnit-b"));
        assertThrows(IllegalArgumentException.class, () -> service.startLearnUnit("journey", "learnUnit-b"));
    }

    @Test
    void failedAssessmentReturnsToLearningAndRetainsBestMastery() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT);
        LearnerLearnUnit existing = new LearnerLearnUnit(
                "journey", "learnUnit-a", LearnerLearnUnitStatus.ASSESSING, 85, 90, 3, null, Instant.EPOCH, null, null);
        when(repository.findLearnerLearnUnit("journey", "learnUnit-a")).thenReturn(Optional.of(existing));
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));

        LearnerLearnUnit result = service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 0, 60, true, true), false);

        assertEquals(LearnerLearnUnitStatus.LEARNING, result.status());
        assertEquals(85, result.masteryScore());
        assertEquals(90, result.bestAssessmentScore());
        assertEquals(4, result.attemptCount());
        verify(repository).upsertLearnerLearnUnit(result);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), eq("learnUnit-a"), any(Instant.class));
    }

    @Test
    void passedAssessmentCompletesLearnUnitAndAdvances() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT);
        LearningPathItem next = item("learnUnit-b", 2, LearningPathItemStatus.PENDING);
        LearnerLearnUnit existing = new LearnerLearnUnit(
                "journey", "learnUnit-a", LearnerLearnUnitStatus.ASSESSING, 70, 70, 1, null, Instant.EPOCH, null, null);
        when(repository.findLearnerLearnUnit("journey", "learnUnit-a")).thenReturn(Optional.of(existing));
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        LearnerLearnUnit result = service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 80, 92, true, true), true);

        assertEquals(LearnerLearnUnitStatus.PASSED, result.status());
        assertEquals(92, result.masteryScore());
        assertEquals(92, result.bestAssessmentScore());
        assertEquals(2, result.attemptCount());
        assertEquals(PassReason.LEARNING, result.passReason());
        verify(repository).updatePathItem("journey", "learnUnit-a", LearningPathItemStatus.COMPLETED);
        verify(repository).updatePathItem("journey", "learnUnit-b", LearningPathItemStatus.CURRENT);
    }

    @Test
    void passingTheLastLearnUnitCompletesTheJourney() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT);
        LearnerLearnUnit existing = new LearnerLearnUnit(
                "journey", "learnUnit-a", LearnerLearnUnitStatus.ASSESSING, 0, 0, 0, null, null, null, null);
        when(repository.findLearnerLearnUnit("journey", "learnUnit-a")).thenReturn(Optional.of(existing));
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current));

        service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 100, 100, true, true), true);

        verify(repository).updatePathItem("journey", "learnUnit-a", LearningPathItemStatus.COMPLETED);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.COMPLETED), eq(null), any(Instant.class));
    }

    @Test
    void skippingEveryLearnUnitCompletesTheJourneyWithoutPassingThem() {
        LearningPathItem first = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT);
        LearningPathItem second = item("learnUnit-b", 2, LearningPathItemStatus.PENDING);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(first));
        when(repository.findPathItem("journey", "learnUnit-b")).thenReturn(Optional.of(
                item("learnUnit-b", 2, LearningPathItemStatus.CURRENT)));
        when(repository.findLearnerLearnUnit("journey", "learnUnit-a")).thenReturn(Optional.empty());
        when(repository.findLearnerLearnUnit("journey", "learnUnit-b")).thenReturn(Optional.empty());
        when(repository.listPath("journey")).thenReturn(
                List.of(first, second),
                List.of(item("learnUnit-a", 1, LearningPathItemStatus.SKIPPED), item("learnUnit-b", 2, LearningPathItemStatus.CURRENT)));

        service.skipLearnUnit("journey", "learnUnit-a");
        service.skipLearnUnit("journey", "learnUnit-b");

        verify(repository).updatePathItem("journey", "learnUnit-a", LearningPathItemStatus.SKIPPED);
        verify(repository).updatePathItem("journey", "learnUnit-b", LearningPathItemStatus.SKIPPED);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.COMPLETED), eq(null), any(Instant.class));
    }

    private LearningPathItem item(String learnUnitCode, int sequence, LearningPathItemStatus status) {
        return new LearningPathItem(learnUnitCode + "-item", "journey", learnUnitCode, sequence, status);
    }

}
