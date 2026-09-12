package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.persistence.LearningRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CurriculumServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final CurriculumGenerator generator = mock(CurriculumGenerator.class);
    private final CurriculumService service = new CurriculumService(repository, generator);

    @Test
    void listingLanguagesDoesNotGenerateAnUnrequestedCatalog() {
        when(repository.listLanguages()).thenReturn(List.of());

        assertEquals(List.of(), service.listLanguages());

        verifyNoInteractions(generator);
    }

    @Test
    void scopesIndependentChapterCurriculumForEachJourney() {
        LearningLanguage language = language("python");
        CurriculumGenerator.GeneratedOutline outline = outline(language);
        when(generator.generateOutline(eq("python"), eq("learn backend APIs"), any())).thenReturn(outline);

        CurriculumGenerator.GeneratedOutline first = service.generateOutlineForJourney(
                "journey-1", "python", "learn backend APIs");
        CurriculumGenerator.GeneratedOutline second = service.generateOutlineForJourney(
                "journey-2", "python", "learn backend APIs");

        assertEquals("journey-1.python-basics", first.chapters().get(0).code());
        assertEquals("journey-1.python.basics", first.learnUnits().get(0).code());
        assertEquals("journey-1.python-basics", first.learnUnits().get(0).chapterCode());
        assertEquals("journey-2.python-basics", second.chapters().get(0).code());
        assertEquals("journey-2.python.basics", second.learnUnits().get(0).code());
        verify(generator, times(2)).generateOutline(eq("python"), eq("learn backend APIs"), any());

        service.persistLanguages(first);
        service.persistJourneyOutline("journey-1", first);
        verify(repository).insertGeneratedLanguages(List.of(language));
        verify(repository).insertGeneratedCatalogForJourney(
                "journey-1", List.of(language), first.chapters(), first.learnUnits());
    }

    @Test
    void persistsConfirmedOutlineWithoutQuestionsOrLessonContent() {
        LearningLanguage language = language("python");
        CurriculumGenerator.GeneratedOutline outline = outline(language);
        when(generator.generateOutline(anyString(), eq("learn APIs"), any())).thenReturn(outline);

        CurriculumGenerator.GeneratedOutline scoped = service.generateOutlineForJourney(
                "journey-1", "python", "learn APIs");
        service.persistJourneyOutline("journey-1", scoped);

        assertTrue(scoped.learnUnits().get(0).lessonIntro().isBlank());
        assertTrue(scoped.learnUnits().get(0).examples().isEmpty());
        verify(repository).insertGeneratedCatalogForJourney(
                "journey-1", List.of(language), scoped.chapters(), scoped.learnUnits());
    }

    @Test
    void rejectsOutlineWithoutChapterOwnershipBeforePersistence() {
        LearningLanguage language = language("python");
        Chapter chapter = new Chapter("chapter", "python-basics", "基础", "基础", 1, List.of());
        LearnUnit unit = new LearnUnit(
                "unit", "python", "python.basics", "missing-chapter", "基础", "基础", 1, List.of(),
                80, null, true, List.of("目标"), "", List.of("概念"), List.of(), true);
        when(generator.generateOutline(anyString(), anyString(), any()))
                .thenReturn(new CurriculumGenerator.GeneratedOutline(List.of(language), List.of(chapter), List.of(unit)));

        assertThrows(IllegalStateException.class,
                () -> service.generateOutlineForJourney("journey-1", "python", "learn APIs"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsCyclicChapterPrerequisitesBeforePersistence() {
        LearningLanguage language = language("python");
        Chapter first = new Chapter("chapter-a", "a", "A", "A", 1, List.of("b"));
        Chapter second = new Chapter("chapter-b", "b", "B", "B", 2, List.of("a"));
        LearnUnit unit = new LearnUnit(
                "unit", "python", "python.basics", "a", "基础", "基础", 1, List.of(),
                80, null, true, List.of("目标"), "", List.of("概念"), List.of(), true);
        when(generator.generateOutline(anyString(), anyString(), any()))
                .thenReturn(new CurriculumGenerator.GeneratedOutline(
                        List.of(language), List.of(first, second), List.of(unit)));

        assertThrows(IllegalStateException.class,
                () -> service.generateOutlineForJourney("journey-1", "python", "learn APIs"));

        verifyNoInteractions(repository);
    }

    @Test
    void lazilyPersistsStructuredContentAndIndependentQuestionsOnce() {
        LearningLanguage language = language("python");
        LearnUnit outline = outline(language).learnUnits().get(0);
        LearnUnit content = detailed(outline);
        Question question = independentQuestion(outline.code());
        when(repository.listLearnUnitsForJourney("journey-1")).thenReturn(List.of(outline));
        when(generator.generateContent(eq(outline), anyString()))
                .thenReturn(new CurriculumGenerator.GeneratedLearnUnitContent(content, List.of(question)));

        LearnUnit result = service.ensureLearnUnitContent("journey-1", outline.code());

        assertEquals(content, result);
        verify(generator).generateContent(eq(outline), anyString());
        verify(repository).persistLearnUnitContent(content, List.of(question));
    }

    @Test
    void returnsCachedContentWithoutCallingTheGenerator() {
        LearningLanguage language = language("python");
        LearnUnit content = detailed(outline(language).learnUnits().get(0));
        when(repository.listLearnUnitsForJourney("journey-1")).thenReturn(List.of(content));

        assertEquals(content, service.ensureLearnUnitContent("journey-1", content.code()));

        verifyNoInteractions(generator);
    }

    @Test
    void rejectsInvalidGeneratedContentBeforeAnyWrite() {
        LearningLanguage language = language("python");
        LearnUnit outline = outline(language).learnUnits().get(0);
        LearnUnit content = detailed(outline).withStructuredContent(
                "", 0, "", List.of(), "", List.of(), "");
        when(repository.listLearnUnitsForJourney("journey-1")).thenReturn(List.of(outline));
        when(generator.generateContent(eq(outline), anyString()))
                .thenReturn(new CurriculumGenerator.GeneratedLearnUnitContent(content, List.of()));

        assertThrows(IllegalStateException.class,
                () -> service.ensureLearnUnitContent("journey-1", outline.code()));

        verify(repository, times(0)).persistLearnUnitContent(any(), any());
    }

    private LearningLanguage language(String code) {
        return new LearningLanguage("language-" + code, code, code, "description", true);
    }

    private CurriculumGenerator.GeneratedOutline outline(LearningLanguage language) {
        Chapter chapter = new Chapter(
                "chapter-python-basics", "python-basics", "基础", "建立基础能力", 1, List.of());
        LearnUnit unit = new LearnUnit(
                "learnUnit-python.basics", language.code(), "python.basics", chapter.code(), "基础", "基础语法",
                1, List.of(), 80, 70, true, List.of("掌握基础"), "", List.of("变量"), List.of(), true);
        return new CurriculumGenerator.GeneratedOutline(List.of(language), List.of(chapter), List.of(unit));
    }

    private LearnUnit detailed(LearnUnit outline) {
        return outline.withStructuredContent(
                "定义并读取变量", 10, "从变量开始", List.of("name = 'Ada'"),
                "定义一个保存姓名的变量", List.of("使用赋值语句"), "完成变量检查");
    }

    private Question independentQuestion(String learnUnitCode) {
        return new Question(
                "question-" + learnUnitCode, learnUnitCode, QuestionType.MULTIPLE_CHOICE, 1,
                "哪个是变量？", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"name\"},{\"id\":\"B\",\"text\":\"123\"}],\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[\"变量\"]", false);
    }
}
