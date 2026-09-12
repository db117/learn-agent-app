package com.example.agent.learning.assessment;

import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingEvaluationRunServiceTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final GenerationRunService generation = new GenerationRunService(executor);

    @AfterEach
    void close() {
        generation.close();
        executor.shutdownNow();
    }

    @Test
    void publishesSafeProgressAndCompletesAfterAssessmentResult() {
        AssessmentService assessments = mock(AssessmentService.class);
        when(assessments.requiresCodingEvaluation("assessment")).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<String>>getArgument(1).accept("MODEL_ACTIVITY");
            invocation.<java.util.function.Consumer<String>>getArgument(1).accept("VALIDATING");
            invocation.<java.util.function.Consumer<String>>getArgument(1).accept("PERSISTING");
            return null;
        }).when(assessments).submit(eq("assessment"), any());

        CodingEvaluationRunService service = new CodingEvaluationRunService(assessments, generation);
        CodingEvaluationRunService.Start started = service.start("assessment");
        List<GenerationEvent> events = generation.events(started.runId())
                .collectList().block(Duration.ofSeconds(1));

        assertNotNull(started.runId());
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("validation")));
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("persistence")));
        assertEquals("COMPLETED", events.get(events.size() - 1).status());
        assertEquals("assessment", events.get(events.size() - 1).resourceId());
        assertTrue(events.stream().noneMatch(event -> event.content().contains("rubric")
                || event.content().contains("Prompt") || event.content().contains("raw")));
        verify(assessments, times(1)).submit(eq("assessment"), any());
    }

    @Test
    void duplicateStartReusesRunAndCancellationProducesTerminalState() throws Exception {
        AssessmentService assessments = mock(AssessmentService.class);
        when(assessments.requiresCodingEvaluation("assessment")).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(assessments).submit(eq("assessment"), any());

        CodingEvaluationRunService service = new CodingEvaluationRunService(assessments, generation);
        CodingEvaluationRunService.Start first = service.start("assessment");
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        CodingEvaluationRunService.Start duplicate = service.start("assessment");
        assertSame(first.runId(), duplicate.runId());
        service.cancel(first.runId());
        List<GenerationEvent> events = generation.events(first.runId())
                .collectList().block(Duration.ofSeconds(1));

        assertEquals("CANCELLED", events.get(events.size() - 1).status());
        assertEquals(1, events.stream().filter(event -> event.eventType().equals("cancelled")).count());
        verify(assessments, times(1)).submit(eq("assessment"), any());
    }
}
