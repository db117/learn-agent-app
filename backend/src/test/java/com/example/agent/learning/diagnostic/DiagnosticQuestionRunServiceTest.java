package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentService;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosticQuestionRunServiceTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final GenerationRunService generation = new GenerationRunService(executor);

    @AfterEach
    void close() {
        generation.close();
        executor.shutdownNow();
    }

    @Test
    void publishesSafeDiagnosticProgressAndCompletesAfterAssessmentPersistence() {
        AssessmentService assessments = mock(AssessmentService.class);
        when(assessments.requiresDiagnosticGeneration("journey")).thenReturn(true);
        Question question = new Question(
                "question", "journey.unit", QuestionType.MULTIPLE_CHOICE, 1, "选择正确的概念", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"正确\"}],\"correctOptionIds\":[\"A\"]}",
                "{\"criterion\":100}", null, null, "[]", true);
        Assessment assessment = new Assessment(
                "assessment", "journey", null, AssessmentType.DIAGNOSTIC,
                AssessmentStatus.CREATED, Instant.now(), null);
        AssessmentService.AssessmentState state = new AssessmentService.AssessmentState(
                assessment, List.of(question), null, List.of(), List.of());
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<String>>getArgument(1).accept("model chunk");
            invocation.<java.util.function.Consumer<AssessmentService.DiagnosticGenerationProgress>>getArgument(2)
                    .accept(new AssessmentService.DiagnosticGenerationProgress("VALIDATING", List.of(question)));
            invocation.<java.util.function.Consumer<AssessmentService.DiagnosticGenerationProgress>>getArgument(2)
                    .accept(new AssessmentService.DiagnosticGenerationProgress("PERSISTING", List.of(question)));
            return state;
        }).when(assessments).createDiagnostic(eq("journey"), any(), any());

        DiagnosticQuestionRunService service = new DiagnosticQuestionRunService(assessments, generation);
        DiagnosticQuestionRunService.DiagnosticStart started = service.start("journey");
        List<GenerationEvent> events = generation.events(started.runId()).collectList().block(Duration.ofSeconds(1));

        assertTrue(started.runId() != null && started.assessment() == null);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("validation")));
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("persistence")));
        assertEquals("COMPLETED", events.get(events.size() - 1).status());
        assertTrue(events.get(events.size() - 1).resourceId().equals("assessment"));
        assertTrue(events.stream().noneMatch(event -> event.content().contains("correctOptionIds")
                || event.content().contains("criterion") || event.preview() != null
                && event.preview().toString().contains("correctOptionIds")));
    }

    @Test
    void keepsExistingFixedAssessmentOnTheSynchronousPath() {
        AssessmentService assessments = mock(AssessmentService.class);
        when(assessments.requiresDiagnosticGeneration("journey")).thenReturn(false);
        AssessmentService.AssessmentState state = new AssessmentService.AssessmentState(
                new Assessment("assessment", "journey", null, AssessmentType.DIAGNOSTIC,
                        AssessmentStatus.CREATED, Instant.now(), null), List.of(), null, List.of(), List.of());
        when(assessments.createDiagnostic("journey")).thenReturn(state);

        DiagnosticQuestionRunService.DiagnosticStart started =
                new DiagnosticQuestionRunService(assessments, generation).start("journey");

        assertEquals(null, started.runId());
        assertEquals(state, started.assessment());
        verify(assessments).createDiagnostic("journey");
    }
}
