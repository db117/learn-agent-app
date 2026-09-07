package com.example.agent.learning.catalog;

import com.example.agent.learning.persistence.LearningRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void generatesIndependentCurriculumForEachJourney() {
        LearningLanguage language = language("python");
        LearningSkill skill = skill("python.basics", "python");
        CurriculumGenerator.GeneratedCurriculum generated = new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of(skill));
        when(generator.generate("python", "learn backend APIs")).thenReturn(generated);

        CurriculumGenerator.GeneratedCurriculum first = service.generateForJourney(
                "journey-1", "python", "learn backend APIs");
        CurriculumGenerator.GeneratedCurriculum second = service.generateForJourney(
                "journey-2", "python", "learn backend APIs");

        assertEquals(language, first.languages().get(0));
        assertEquals("journey-1.python.basics", first.skills().get(0).code());
        assertEquals("journey-2.python.basics", second.skills().get(0).code());
        assertNotEquals(first.skills().get(0).code(), second.skills().get(0).code());
        verify(generator, times(2)).generate("python", "learn backend APIs");

        service.persistLanguages(first);
        service.persistJourneyCurriculum("journey-1", first);
        verify(repository).insertGeneratedCatalog(List.of(language), List.of());
        verify(repository).insertGeneratedCatalogForJourney("journey-1", List.of(language), first.skills());
    }

    @Test
    void rejectsCatalogThatDoesNotCoverEveryLanguage() {
        LearningLanguage language = language("rust");
        when(generator.generate("rust", "")).thenReturn(new CurriculumGenerator.GeneratedCurriculum(
                List.of(language), List.of()));

        assertThrows(IllegalStateException.class, () -> service.generateForJourney("journey", "rust", ""));
    }

    private LearningLanguage language(String code) {
        return new LearningLanguage("language-" + code, code, code, "description", true);
    }

    private LearningSkill skill(String code, String languageCode) {
        return new LearningSkill(
                "skill-" + code, languageCode, code, code, "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
    }
}
