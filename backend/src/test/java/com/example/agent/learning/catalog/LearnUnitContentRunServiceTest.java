package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import com.example.agent.learning.progress.ProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearnUnitContentRunServiceTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final GenerationRunService generation = new GenerationRunService(executor);
    private final CurriculumService curriculum = mock(CurriculumService.class);
    private final ProgressService progress = mock(ProgressService.class);
    private final LearnUnitContentRunService service =
            new LearnUnitContentRunService(curriculum, progress, generation);

    @AfterEach
    void close() {
        generation.close();
        executor.shutdownNow();
    }

    @Test
    void publishesSafePreviewAndAppliesPathActionAfterPersistence() {
        LearnUnit outline = outline();
        LearnUnit content = outline.withStructuredContent(
                "变量能力", 10, "教学介绍", List.of("const value = 1;"),
                "完成练习", List.of("先声明变量"), "完成检查");
        Question question = new Question(
                "q-1", outline.code(), QuestionType.MULTIPLE_CHOICE, 1, "选择变量", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        CurriculumGenerator.GeneratedLearnUnitContent generated =
                new CurriculumGenerator.GeneratedLearnUnitContent(content, List.of(question));
        when(curriculum.learnUnitOutline("journey-1", outline.code())).thenReturn(outline);
        when(curriculum.generateLearnUnitContent(eq("journey-1"), eq(outline.code()), any()))
                .thenReturn(generated);
        when(curriculum.persistLearnUnitContent(eq("journey-1"), eq(outline.code()), eq(generated)))
                .thenReturn(content);

        GenerationRunService.StartResult started = service.start(
                "journey-1", outline.code(), LearnUnitContentRunService.EntryAction.CONTINUE);
        List<GenerationEvent> events = service.events(started.run().id(), -1)
                .collectList().block();

        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("validation")));
        GenerationEvent validation = events.stream()
                .filter(event -> event.eventType().equals("validation")).findFirst().orElseThrow();
        LearnUnitContentPreview preview = assertInstanceOf(LearnUnitContentPreview.class, validation.preview());
        assertEquals(1, preview.independentQuestionCount());
        assertTrue(events.stream().noneMatch(event -> event.content().contains("correctOptionIds")));
        verify(curriculum).persistLearnUnitContent("journey-1", outline.code(), generated);
        verify(progress).startLearnUnit("journey-1", outline.code());
        assertEquals("COMPLETED", events.get(events.size() - 1).status());
    }

    @Test
    void reusesTheActiveRunForTheSameLearnUnit() {
        LearnUnit outline = outline();
        when(curriculum.learnUnitOutline("journey-1", outline.code())).thenReturn(outline);
        when(curriculum.generateLearnUnitContent(eq("journey-1"), eq(outline.code()), any()))
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    return new CurriculumGenerator.GeneratedLearnUnitContent(
                            outline.withStructuredContent("ability", 5, "intro", List.of("example"),
                                    "practice", List.of(), "check"), List.of(question(outline.code())));
                });
        when(curriculum.persistLearnUnitContent(eq("journey-1"), eq(outline.code()), any()))
                .thenReturn(outline);

        GenerationRunService.StartResult first = service.start(
                "journey-1", outline.code(), LearnUnitContentRunService.EntryAction.START);
        GenerationRunService.StartResult duplicate = service.start(
                "journey-1", outline.code(), LearnUnitContentRunService.EntryAction.CONTINUE);

        assertTrue(!duplicate.created());
        assertEquals(first.run().id(), duplicate.run().id());
    }

    private LearnUnit outline() {
        return new LearnUnit(
                "unit-1", "typescript", "typescript.variables", "chapter-1", "Variables", "Basics",
                1, List.of(), 80, null, true, List.of("Use variables"), "", List.of("variables"), List.of(), true);
    }

    private Question question(String code) {
        return new Question(
                "q-" + code, code, QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
    }
}
