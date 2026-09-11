package com.example.agent.learning.progress;

import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.path.LearningPhase;
import com.example.agent.learning.path.GuidedPracticeEntry;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.workflow.WorkflowTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final ProgressService service = new ProgressService(repository, new DeterministicLearningPathPlanner());

    @BeforeEach
    void journeyExists() {
        when(repository.findJourney("journey")).thenReturn(Optional.of(new LearningJourney(
                "journey", "user", "typescript", "goal", JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH)));
    }

    @Test
    void skipKeepsMasteryAndMovesToTheNextLearnUnitInTheSamePath() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 72, 78, 2);
        LearningPathItem next = item("learnUnit-b", 2, LearningPathItemStatus.PENDING, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        service.skipLearnUnit("journey", "learnUnit-a");

        ArgumentCaptor<LearningPathItem> updates = ArgumentCaptor.forClass(LearningPathItem.class);
        verify(repository, times(2)).updatePathItem(updates.capture());
        LearningPathItem skipped = updates.getAllValues().stream()
                .filter(item -> item.learnUnitCode().equals("learnUnit-a")).findFirst().orElseThrow();
        LearningPathItem advanced = updates.getAllValues().stream()
                .filter(item -> item.learnUnitCode().equals("learnUnit-b")).findFirst().orElseThrow();
        assertEquals(LearningPathItemStatus.SKIPPED, skipped.status());
        assertEquals(72, skipped.masteryScore());
        assertEquals(78, skipped.bestAssessmentScore());
        assertEquals(2, skipped.attemptCount());
        assertEquals(null, skipped.passReason());
        assertEquals(LearningPathItemStatus.CURRENT, advanced.status());
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), any(Instant.class));
    }

    @Test
    void cannotSkipOrStartAPendingLearnUnit() {
        LearningPathItem pending = item("learnUnit-b", 2, LearningPathItemStatus.PENDING, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-b")).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class, () -> service.skipLearnUnit("journey", "learnUnit-b"));
        assertThrows(IllegalArgumentException.class, () -> service.startLearnUnit("journey", "learnUnit-b"));
    }

    @Test
    void cannotSkipWhileTheCurrentLearnUnitHasAnOpenAttempt() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.hasOpenLearnUnitAttempt("journey", "learnUnit-a")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.skipLearnUnit("journey", "learnUnit-a"));
        verify(repository, times(0)).updatePathItem(any(LearningPathItem.class));
        verify(repository, times(0)).insertWorkflowTransition(any(WorkflowTransition.class));
    }

    @Test
    void failedAssessmentRetainsBestMasteryOnTheCurrentPathItem() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 85, 90, 3);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));

        LearningPathItem result = service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 0, 60, true, true), false);

        assertEquals(LearningPathItemStatus.CURRENT, result.status());
        assertEquals(85, result.masteryScore());
        assertEquals(90, result.bestAssessmentScore());
        assertEquals(4, result.attemptCount());
        verify(repository).updatePathItem(result);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), any(Instant.class));
    }

    @Test
    void passedAssessmentCompletesThePathItemAndAdvances() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 70, 70, 1);
        LearningPathItem next = item("learnUnit-b", 2, LearningPathItemStatus.PENDING, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        LearningPathItem result = service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 80, 92, true, true), true);

        assertEquals(LearningPathItemStatus.COMPLETED, result.status());
        assertEquals(92, result.masteryScore());
        assertEquals(92, result.bestAssessmentScore());
        assertEquals(2, result.attemptCount());
        assertEquals(PassReason.LEARNING, result.passReason());
        ArgumentCaptor<LearningPathItem> updates = ArgumentCaptor.forClass(LearningPathItem.class);
        verify(repository, times(2)).updatePathItem(updates.capture());
        assertEquals(LearningPathItemStatus.CURRENT, updates.getAllValues().stream()
                .filter(item -> item.learnUnitCode().equals("learnUnit-b")).findFirst().orElseThrow().status());
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), any(Instant.class));
    }

    @Test
    void passAndNextTransitionsUsePathStates() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 0, 0, 0);
        LearningPathItem next = item("learnUnit-b", 2, LearningPathItemStatus.PENDING, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 0, 100, true, false), true);

        ArgumentCaptor<WorkflowTransition> transitions = ArgumentCaptor.forClass(WorkflowTransition.class);
        verify(repository, times(2)).insertWorkflowTransition(transitions.capture());
        assertEquals("CURRENT", transitions.getAllValues().get(0).fromState());
        assertEquals("PASS", transitions.getAllValues().get(0).action());
        assertEquals("COMPLETED", transitions.getAllValues().get(0).toState());
        assertEquals("PENDING", transitions.getAllValues().get(1).fromState());
        assertEquals("NEXT", transitions.getAllValues().get(1).action());
        assertEquals("CURRENT", transitions.getAllValues().get(1).toState());
    }

    @Test
    void rejectsAnAssessmentSubmittedAfterThePathItemWasSkipped() {
        LearningPathItem skipped = item("learnUnit-a", 1, LearningPathItemStatus.SKIPPED, 0, 0, 1);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(skipped));

        assertThrows(IllegalArgumentException.class, () -> service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 100, 100, true, true), true));

        verify(repository, times(0)).updatePathItem(any(LearningPathItem.class));
    }

    @Test
    void rejectsAStaleDiagnosticResultForASkippedPathItem() {
        LearningPathItem skipped = item("learnUnit-a", 1, LearningPathItemStatus.SKIPPED, 0, 0, 1);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(skipped));

        assertThrows(IllegalArgumentException.class, () -> service.recordDiagnosticResult(
                "journey", "learnUnit-a", new AssessmentScore(100, 100, 100, true, true), true));

        verify(repository, times(0)).updatePathItem(any(LearningPathItem.class));
    }

    @Test
    void passingTheLastPathItemCompletesTheJourney() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current));

        service.recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 100, 100, true, true), true);

        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.COMPLETED), any(Instant.class));
    }

    @Test
    void nextIsIdempotentAfterPassAndReturnsTheServerSelectedCurrentItem() {
        LearningPathItem closed = item("learnUnit-a", 1, LearningPathItemStatus.COMPLETED, 100, 100, 1);
        LearningPathItem current = item("learnUnit-b", 2, LearningPathItemStatus.CURRENT, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(closed));
        when(repository.listPath("journey")).thenReturn(List.of(closed, current));

        assertEquals(current, service.nextLearnUnit("journey", "learnUnit-a"));
        verify(repository, times(0)).updatePathItem(any(LearningPathItem.class));
        verify(repository, times(0)).insertWorkflowTransition(any(WorkflowTransition.class));
    }

    @Test
    void nextRejectsAnOpenCurrentLearnUnit() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));

        assertThrows(IllegalArgumentException.class, () -> service.nextLearnUnit("journey", "learnUnit-a"));
        verify(repository, times(0)).updatePathItem(any(LearningPathItem.class));
    }

    @Test
    void skippingEveryPathItemCompletesTheJourneyWithoutPassingThem() {
        LearningPathItem first = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 0, 0, 0);
        LearningPathItem second = item("learnUnit-b", 2, LearningPathItemStatus.PENDING, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(first));
        when(repository.findPathItem("journey", "learnUnit-b")).thenReturn(Optional.of(
                item("learnUnit-b", 2, LearningPathItemStatus.CURRENT, 0, 0, 0)));
        when(repository.listPath("journey")).thenReturn(
                List.of(first, second),
                List.of(item("learnUnit-a", 1, LearningPathItemStatus.SKIPPED, 0, 0, 0),
                        item("learnUnit-b", 2, LearningPathItemStatus.CURRENT, 0, 0, 0)));

        service.skipLearnUnit("journey", "learnUnit-a");
        service.skipLearnUnit("journey", "learnUnit-b");

        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.COMPLETED), any(Instant.class));
    }

    @Test
    void advancesOnlyTheCurrentPhaseAndRecordsARequestedSkip() {
        LearningPathItem current = item("learnUnit-a", 1, LearningPathItemStatus.CURRENT, 0, 0, 0);
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));

        LearningPathItem result = service.advancePhase("journey", "learnUnit-a", LearningPhase.EXPLANATION);

        assertEquals(LearningPhase.EXAMPLE, result.learningPhase());
        verify(repository).updatePathItem(result);

        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(result));
        LearningPathItem skipped = service.skipPhase("journey", "learnUnit-a", LearningPhase.EXAMPLE);

        assertEquals(LearningPhase.GUIDED_PRACTICE, skipped.learningPhase());
        assertEquals(List.of(LearningPhase.EXAMPLE), skipped.skippedPhases());
        verify(repository).updatePathItem(skipped);
    }

    @Test
    void skippingIndependentCheckLeavesTheUnitCurrentAndUnresolved() {
        LearningPathItem current = new LearningPathItem(
                "item", "journey", "learnUnit-a", 1, LearningPathItemStatus.CURRENT,
                0, 0, 0, null, Instant.EPOCH, null, null, LearningPhase.INDEPENDENT_CHECK,
                List.of(), List.of());
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));

        LearningPathItem result = service.skipPhase("journey", "learnUnit-a", LearningPhase.INDEPENDENT_CHECK);

        assertEquals(LearningPathItemStatus.CURRENT, result.status());
        assertEquals(LearningPhase.INDEPENDENT_CHECK, result.learningPhase());
        assertEquals(List.of(LearningPhase.INDEPENDENT_CHECK), result.skippedPhases());
        verify(repository).updatePathItem(result);
        verify(repository, never()).updateJourney(anyString(), any(), any(Instant.class));
    }

    @Test
    void recordsGuidedPracticeFeedbackWithoutAssessmentOrScore() {
        LearningPathItem current = new LearningPathItem(
                "item", "journey", "learnUnit-a", 1, LearningPathItemStatus.CURRENT,
                0, 0, 0, null, Instant.EPOCH, null, null, LearningPhase.GUIDED_PRACTICE,
                List.of(), List.of());
        when(repository.findPathItem("journey", "learnUnit-a")).thenReturn(Optional.of(current));

        LearningPathItem result = service.recordGuidedPractice("journey", "learnUnit-a", "name = 'Ada'");

        assertEquals(1, result.guidedPracticeEntries().size());
        GuidedPracticeEntry entry = result.guidedPracticeEntries().get(0);
        assertEquals("name = 'Ada'", entry.response());
        assertTrue(entry.feedback().contains("已记录"));
        verify(repository).updatePathItem(result);
        verify(repository, never()).insertAssessment(any());
    }

    private LearningPathItem item(
            String learnUnitCode, int sequence, LearningPathItemStatus status,
            int masteryScore, int bestAssessmentScore, int attemptCount) {
        return new LearningPathItem(
                learnUnitCode + "-item", "journey", learnUnitCode, sequence, status,
                masteryScore, bestAssessmentScore, attemptCount, null, Instant.EPOCH, null, null);
    }
}
