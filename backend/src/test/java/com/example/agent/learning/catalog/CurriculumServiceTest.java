package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.persistence.LearningRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    void generatesIndependentCurriculumForEachJourney() {
        LearningLanguage language = language("python");
        LearnUnit learnUnit = learnUnit("python.basics", "python");
        CurriculumGenerator.GeneratedCurriculum generated = new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of(learnUnit), List.of(choiceQuestion(learnUnit)));
        when(generator.generate("python", "learn backend APIs")).thenReturn(generated);

        CurriculumGenerator.GeneratedCurriculum first = service.generateForJourney(
                "journey-1", "python", "learn backend APIs");
        CurriculumGenerator.GeneratedCurriculum second = service.generateForJourney(
                "journey-2", "python", "learn backend APIs");

        assertEquals(language, first.languages().get(0));
        assertEquals("journey-1.python.basics", first.learnUnits().get(0).code());
        assertEquals("journey-2.python.basics", second.learnUnits().get(0).code());
        assertNotEquals(first.learnUnits().get(0).code(), second.learnUnits().get(0).code());
        verify(generator, times(2)).generate("python", "learn backend APIs");

        service.persistLanguages(first);
        service.persistJourneyCurriculum("journey-1", first);
        verify(repository).insertGeneratedCatalog(List.of(language), List.of());
        verify(repository).insertGeneratedCatalogForJourney("journey-1", List.of(language), first.learnUnits());
    }

    @Test
    void rejectsCatalogThatDoesNotCoverEveryLanguage() {
        LearningLanguage language = language("rust");
        when(generator.generate("rust", "")).thenReturn(new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of(), List.of()));

        assertThrows(IllegalStateException.class, () -> service.generateForJourney("journey", "rust", ""));
    }

    @Test
    void scopesAndPersistsGeneratedQuestionsWithTheJourney() {
        LearningLanguage language = language("python");
        LearnUnit learnUnit = learnUnit("python.basics", "python", null);
        Question choice = new Question(
                "choice", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", true);
        when(generator.generate("python", "learn APIs")).thenReturn(new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of(learnUnit), List.of(choice)));

        CurriculumGenerator.GeneratedCurriculum generated = service.generateForJourney(
                "journey-1", "python", "learn APIs");

        assertEquals("journey-1.python.basics", generated.learnUnits().get(0).code());
        assertEquals("journey-1.choice", generated.questions().get(0).id());
        assertEquals("journey-1.python.basics", generated.questions().get(0).learnUnitCode());

        service.persistJourneyCurriculum("journey-1", generated);

        verify(repository).insertGeneratedCatalogForJourney(
                "journey-1", List.of(language), generated.learnUnits());
        verify(repository).insertGeneratedQuestion(generated.questions().get(0));
    }

    @Test
    void persistsConfirmedOutlineWithoutQuestionsOrLessonContent() {
        LearningLanguage language = language("python");
        LearnUnit outlineUnit = new LearnUnit(
                "unit", "python", "python.basics", "基础", "基础语法", 1, List.of(), 80, 70, true,
                List.of("掌握基础"), "", List.of("变量"), List.of(), true);
        CurriculumGenerator.GeneratedOutline outline = new CurriculumGenerator.GeneratedOutline(
                List.of(language), List.of(outlineUnit));
        when(generator.generateOutline(anyString(), eq("learn APIs"), any())).thenReturn(outline);

        CurriculumGenerator.GeneratedOutline scoped = service.generateOutlineForJourney(
                "journey-1", "python", "learn APIs");
        service.persistJourneyOutline("journey-1", scoped);

        assertEquals("journey-1.python.basics", scoped.learnUnits().get(0).code());
        assertTrue(scoped.learnUnits().get(0).lessonIntro().isBlank());
        assertTrue(scoped.learnUnits().get(0).examples().isEmpty());
        verify(repository).insertGeneratedCatalogForJourney("journey-1", List.of(language), scoped.learnUnits());
        verify(repository, org.mockito.Mockito.never()).insertGeneratedQuestion(any());
    }

    @Test
    void rejectsCodingQuestionWhenLearnUnitHasNoCodingObjectiveBeforePersistence() {
        LearningLanguage language = language("python");
        LearnUnit learnUnit = learnUnit("python.reading", "python", null);
        Question coding = new Question(
                "coding", learnUnit.code(), QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":100}", "python", "", "[]", true);
        when(generator.generate("python", "read docs")).thenReturn(new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of(learnUnit), List.of(coding)));

        assertThrows(IllegalStateException.class,
                () -> service.generateForJourney("journey-1", "python", "read docs"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsEmptyQuestionSetBeforePersistence() {
        LearningLanguage language = language("python");
        LearnUnit learnUnit = learnUnit("python.reading", "python", null);
        when(generator.generate("python", "read docs")).thenReturn(new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of(learnUnit), List.of()));

        assertThrows(IllegalStateException.class,
                () -> service.generateForJourney("journey-1", "python", "read docs"));

        verifyNoInteractions(repository);
    }

    private LearningLanguage language(String code) {
        return new LearningLanguage("language-" + code, code, code, "description", true);
    }

    private LearnUnit learnUnit(String code, String languageCode) {
        return learnUnit(code, languageCode, null);
    }

    private Question choiceQuestion(LearnUnit learnUnit) {
        return new Question(
                "choice", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", true);
    }

    private LearnUnit learnUnit(String code, String languageCode, Integer minCodingScore) {
        return new LearnUnit(
                "learnUnit-" + code, languageCode, code, code, "description", 1, List.of(),
                80, minCodingScore, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
    }
}
