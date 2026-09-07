package com.example.agent.learning.progress;

import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearnerSkill;
import com.example.agent.learning.journey.LearnerSkillStatus;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import org.junit.jupiter.api.Test;
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

    @Test
    void skipKeepsMasteryAndMovesToTheNextSkill() {
        LearningPathItem current = item("skill-a", 1, LearningPathItemStatus.CURRENT);
        LearningPathItem next = item("skill-b", 2, LearningPathItemStatus.PENDING);
        LearnerSkill existing = new LearnerSkill(
                "journey", "skill-a", LearnerSkillStatus.LEARNING, 72, 78, 2, null, Instant.EPOCH, null, null);
        when(repository.findPathItem("journey", "skill-a")).thenReturn(Optional.of(current));
        when(repository.findLearnerSkill("journey", "skill-a")).thenReturn(Optional.of(existing));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        service.skipSkill("journey", "skill-a");

        ArgumentCaptor<LearnerSkill> learnerSkill = ArgumentCaptor.forClass(LearnerSkill.class);
        verify(repository).upsertLearnerSkill(learnerSkill.capture());
        assertEquals(LearnerSkillStatus.SKIPPED, learnerSkill.getValue().status());
        assertEquals(72, learnerSkill.getValue().masteryScore());
        assertEquals(78, learnerSkill.getValue().bestAssessmentScore());
        assertEquals(2, learnerSkill.getValue().attemptCount());
        assertEquals(null, learnerSkill.getValue().passReason());
        verify(repository).updatePathItem("journey", "skill-a", LearningPathItemStatus.SKIPPED);
        verify(repository).updatePathItem("journey", "skill-b", LearningPathItemStatus.CURRENT);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), eq("skill-b"), any(Instant.class));
    }

    @Test
    void cannotSkipOrStartAPendingSkill() {
        LearningPathItem pending = item("skill-b", 2, LearningPathItemStatus.PENDING);
        when(repository.findPathItem("journey", "skill-b")).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class, () -> service.skipSkill("journey", "skill-b"));
        assertThrows(IllegalArgumentException.class, () -> service.startSkill("journey", "skill-b"));
    }

    @Test
    void failedAssessmentReturnsToLearningAndRetainsBestMastery() {
        LearningPathItem current = item("skill-a", 1, LearningPathItemStatus.CURRENT);
        LearnerSkill existing = new LearnerSkill(
                "journey", "skill-a", LearnerSkillStatus.ASSESSING, 85, 90, 3, null, Instant.EPOCH, null, null);
        when(repository.findLearnerSkill("journey", "skill-a")).thenReturn(Optional.of(existing));
        when(repository.findPathItem("journey", "skill-a")).thenReturn(Optional.of(current));

        LearnerSkill result = service.recordSkillAssessment(
                "journey", "skill-a", new AssessmentScore(100, 0, 60, true, true), false);

        assertEquals(LearnerSkillStatus.LEARNING, result.status());
        assertEquals(85, result.masteryScore());
        assertEquals(90, result.bestAssessmentScore());
        assertEquals(4, result.attemptCount());
        verify(repository).upsertLearnerSkill(result);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.ACTIVE), eq("skill-a"), any(Instant.class));
    }

    @Test
    void passedAssessmentCompletesSkillAndAdvances() {
        LearningPathItem current = item("skill-a", 1, LearningPathItemStatus.CURRENT);
        LearningPathItem next = item("skill-b", 2, LearningPathItemStatus.PENDING);
        LearnerSkill existing = new LearnerSkill(
                "journey", "skill-a", LearnerSkillStatus.ASSESSING, 70, 70, 1, null, Instant.EPOCH, null, null);
        when(repository.findLearnerSkill("journey", "skill-a")).thenReturn(Optional.of(existing));
        when(repository.findPathItem("journey", "skill-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current, next));

        LearnerSkill result = service.recordSkillAssessment(
                "journey", "skill-a", new AssessmentScore(100, 80, 92, true, true), true);

        assertEquals(LearnerSkillStatus.PASSED, result.status());
        assertEquals(92, result.masteryScore());
        assertEquals(92, result.bestAssessmentScore());
        assertEquals(2, result.attemptCount());
        assertEquals(PassReason.LEARNING, result.passReason());
        verify(repository).updatePathItem("journey", "skill-a", LearningPathItemStatus.COMPLETED);
        verify(repository).updatePathItem("journey", "skill-b", LearningPathItemStatus.CURRENT);
    }

    @Test
    void passingTheLastSkillCompletesTheJourney() {
        LearningPathItem current = item("skill-a", 1, LearningPathItemStatus.CURRENT);
        LearnerSkill existing = new LearnerSkill(
                "journey", "skill-a", LearnerSkillStatus.ASSESSING, 0, 0, 0, null, null, null, null);
        when(repository.findLearnerSkill("journey", "skill-a")).thenReturn(Optional.of(existing));
        when(repository.findPathItem("journey", "skill-a")).thenReturn(Optional.of(current));
        when(repository.listPath("journey")).thenReturn(List.of(current));

        service.recordSkillAssessment(
                "journey", "skill-a", new AssessmentScore(100, 100, 100, true, true), true);

        verify(repository).updatePathItem("journey", "skill-a", LearningPathItemStatus.COMPLETED);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.COMPLETED), eq(null), any(Instant.class));
    }

    @Test
    void skippingEverySkillCompletesTheJourneyWithoutPassingThem() {
        LearningPathItem first = item("skill-a", 1, LearningPathItemStatus.CURRENT);
        LearningPathItem second = item("skill-b", 2, LearningPathItemStatus.PENDING);
        when(repository.findPathItem("journey", "skill-a")).thenReturn(Optional.of(first));
        when(repository.findPathItem("journey", "skill-b")).thenReturn(Optional.of(
                item("skill-b", 2, LearningPathItemStatus.CURRENT)));
        when(repository.findLearnerSkill("journey", "skill-a")).thenReturn(Optional.empty());
        when(repository.findLearnerSkill("journey", "skill-b")).thenReturn(Optional.empty());
        when(repository.listPath("journey")).thenReturn(
                List.of(first, second),
                List.of(item("skill-a", 1, LearningPathItemStatus.SKIPPED), item("skill-b", 2, LearningPathItemStatus.CURRENT)));

        service.skipSkill("journey", "skill-a");
        service.skipSkill("journey", "skill-b");

        verify(repository).updatePathItem("journey", "skill-a", LearningPathItemStatus.SKIPPED);
        verify(repository).updatePathItem("journey", "skill-b", LearningPathItemStatus.SKIPPED);
        verify(repository).updateJourney(eq("journey"), eq(JourneyStatus.COMPLETED), eq(null), any(Instant.class));
    }

    private LearningPathItem item(String skillCode, int sequence, LearningPathItemStatus status) {
        return new LearningPathItem(skillCode + "-item", "journey", skillCode, sequence, status);
    }

}
