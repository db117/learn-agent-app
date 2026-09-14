package com.example.agent.learning.journey;

import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningJourneyQueryServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final ProgressService progress = mock(ProgressService.class);
    private final LearningJourneyQueryService service = new LearningJourneyQueryService(repository, progress);

    @Test
    void assemblesJourneyFactsAndDelegatesProgressRules() {
        LearningJourney journey = journey("journey-1");
        Chapter chapter = new Chapter("chapter-1", "basics", "Basics", "goal", 1, List.of());
        LearnUnit completed = learnUnit("unit-completed", "basics", 1);
        LearnUnit skipped = learnUnit("unit-skipped", "basics", 2);
        LearningPathItem completedPath = new LearningPathItem(
                "path-1", "journey-1", completed.code(), 1, LearningPathItemStatus.COMPLETED);
        LearningPathItem skippedPath = new LearningPathItem(
                "path-2", "journey-1", skipped.code(), 2, LearningPathItemStatus.SKIPPED);
        Assessment synthesis = new Assessment(
                "synthesis-1", "journey-1", null, "basics", AssessmentType.CHAPTER_SYNTHESIS,
                AssessmentStatus.CREATED, Instant.EPOCH, null);

        when(repository.findJourney("journey-1")).thenReturn(Optional.of(journey));
        when(repository.findProfile("journey-1")).thenReturn(Optional.empty());
        when(repository.listPath("journey-1")).thenReturn(List.of(completedPath, skippedPath));
        when(repository.listLearnUnitsForJourney("journey-1")).thenReturn(List.of(completed, skipped));
        when(repository.listChaptersForJourney("journey-1")).thenReturn(List.of(chapter));
        when(repository.findDiagnosticAssessment("journey-1")).thenReturn(Optional.empty());
        when(repository.findLatestChapterSynthesisAssessment("journey-1", "basics"))
                .thenReturn(Optional.of(synthesis));
        when(progress.isChapterSynthesisEligible("journey-1", "basics")).thenReturn(true);
        when(progress.hasPassedChapterSynthesis("journey-1", "basics")).thenReturn(false);

        LearningJourneySnapshot snapshot = service.journey("journey-1");
        LearningJourneySnapshot.ChapterSnapshot result = snapshot.chapters().get(0);

        assertEquals(journey, snapshot.journey());
        assertEquals(2, result.learnUnits().size());
        assertEquals(2, result.path().size());
        assertEquals(1, result.completedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(1, result.unresolvedCount());
        assertTrue(result.synthesisAvailable());
        assertFalse(result.synthesisCompleted());
        assertEquals("synthesis-1", result.synthesisAssessmentId());
        verify(progress).isChapterSynthesisEligible("journey-1", "basics");
        verify(progress).hasPassedChapterSynthesis("journey-1", "basics");
    }

    @Test
    void readsLearnUnitFactsOnlyFromTheRequestedJourney() {
        LearnUnit target = learnUnit("unit-1", "basics", 1);
        LearningPathItem path = new LearningPathItem(
                "path-1", "journey-1", target.code(), 1, LearningPathItemStatus.CURRENT);
        AssessmentAttempt attempt = new AssessmentAttempt(
                "attempt-1", "assessment-1", "journey-1", target.code(), 1,
                80, null, 80, true, Instant.EPOCH, Instant.EPOCH);
        QuestionAttempt questionAttempt = new QuestionAttempt(
                "question-1", "attempt-1", "{}", 80, 100, "good", true,
                null, null, null);

        when(repository.findJourney("journey-1")).thenReturn(Optional.of(journey("journey-1")));
        when(repository.listLearnUnitsForJourney("journey-1")).thenReturn(List.of(target));
        when(repository.findPathItem("journey-1", target.code())).thenReturn(Optional.of(path));
        when(repository.listAttemptsForLearnUnit("journey-1", target.code())).thenReturn(List.of(attempt));
        when(repository.listQuestionAttemptsForLearnUnit("journey-1", target.code()))
                .thenReturn(List.of(questionAttempt));

        LearnUnitSnapshot snapshot = service.learnUnit("journey-1", target.code());

        assertEquals(target, snapshot.learnUnit());
        assertEquals(path, snapshot.pathItem());
        assertEquals(List.of(attempt), snapshot.attempts());
        assertEquals(List.of(questionAttempt), snapshot.questionAttempts());
        assertThrows(IllegalArgumentException.class, () -> service.learnUnit("journey-1", "unit-from-journey-2"));
    }

    private LearningJourney journey(String id) {
        return new LearningJourney(id, "user", "typescript", "goal", JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
    }

    private LearnUnit learnUnit(String code, String chapterCode, int sequence) {
        return new LearnUnit(
                code, "typescript", code, chapterCode, code, "description", sequence, List.of(),
                80, null, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
    }
}
