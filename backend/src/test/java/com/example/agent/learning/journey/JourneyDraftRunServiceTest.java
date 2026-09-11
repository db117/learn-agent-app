package com.example.agent.learning.journey;

import com.example.agent.config.AppProperties;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JourneyDraftRunServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotPersistUntilTheUserConfirmsTheOutline() {
        CurriculumService curriculum = mock(CurriculumService.class);
        LearningJourneyService journeys = mock(LearningJourneyService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            JourneyDraftInput input = new JourneyDraftInput(
                    "python", "Learn APIs", "中文", 2, "会写后端", "掌握 Python Web 开发");
            CurriculumGenerator.GeneratedOutline outline = outline();
            when(journeys.learningContext(input)).thenReturn("context");
            when(curriculum.generateOutlineForJourney(anyString(), eq("python"), anyString(), any()))
                    .thenReturn(outline);
            JourneyDraftRunService service = new JourneyDraftRunService(
                    curriculum, journeys, new AppProperties(tempDir.toString(), "learning.db", "local", "app"), executor);

            String runId = service.start("local", input);
            service.events(runId)
                    .filter(event -> event.eventType().equals("outline_ready"))
                    .next()
                    .block(Duration.ofSeconds(2));

            verify(journeys, never()).confirmOutline(anyString(), anyString(), any(), any());

            var nextOutline = service.events(runId)
                    .filter(event -> event.eventType().equals("outline_ready"))
                    .skip(1)
                    .next();
            service.guide(runId, "增加泛型和 API 错误处理");
            assertTrue(nextOutline.block(Duration.ofSeconds(2)) != null);
            var contexts = forClass(String.class);
            verify(curriculum, times(2)).generateOutlineForJourney(
                    anyString(), eq("python"), contexts.capture(), any());
            assertTrue(contexts.getAllValues().get(1).contains("增加泛型和 API 错误处理"));

            service.confirm(runId);
            assertTrue(service.events(runId)
                    .filter(event -> event.eventType().equals("confirmed"))
                    .next()
                    .block(Duration.ofSeconds(2)) != null);
            verify(journeys).confirmOutline(eq("local"), eq(runId), eq(input), eq(outline));
        } finally {
            executor.shutdownNow();
        }
    }

    private CurriculumGenerator.GeneratedOutline outline() {
        LearningLanguage language = new LearningLanguage("language-python", "python", "Python", "Python path", true);
        Chapter chapter = new Chapter("chapter-python", "python-basics", "基础", "基础语法", 1, List.of());
        LearnUnit unit = new LearnUnit(
                "unit-python", "python", "python.basics", chapter.code(), "基础", "基础语法", 1, List.of(), 80, null, true,
                List.of("掌握基础"), "", List.of("变量"), List.of(), true);
        return new CurriculumGenerator.GeneratedOutline(List.of(language), List.of(chapter), List.of(unit));
    }
}
