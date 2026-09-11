package com.example.agent.llm;

import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.llm.infrastructure.LlmCurriculumGenerator;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmCurriculumGeneratorTest {

    @Test
    void generatesOrderedChaptersAndAssignsEachOutlineToItsChapter() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {
                  "languages":[{"code":"python","name":"Python","description":"Python path"}],
                  "chapters":[
                    {"code":"python-basics","name":"基础","goal":"建立基础能力","sequence":1,"prerequisiteChapterCodes":[]},
                    {"code":"python-data","name":"数据","goal":"处理数据","sequence":2,"prerequisiteChapterCodes":["python-basics"]}
                  ],
                  "learnUnits":[
                    {"languageCode":"python","chapterCode":"python-basics","code":"python.variables","name":"变量","description":"变量基础",
                     "sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,"minCodingScore":null,
                     "learningObjectives":["使用变量"],"keyConcepts":["绑定"]},
                    {"languageCode":"python","chapterCode":"python-data","code":"python.lists","name":"列表","description":"列表基础",
                     "sequence":2,"prerequisiteLearnUnitCodes":["python.variables"],"passScore":80,"minCodingScore":null,
                     "learningObjectives":["使用列表"],"keyConcepts":["索引"]}
                  ]
                }
                """));

        CurriculumGenerator.GeneratedOutline result = generator.generateOutline("python", "learn backend APIs");

        assertEquals(List.of("python-basics", "python-data"),
                result.chapters().stream().map(Chapter::code).toList());
        assertEquals("python-basics", result.learnUnits().get(0).chapterCode());
        assertEquals("python-data", result.learnUnits().get(1).chapterCode());
    }

    @Test
    void generatesAnOutlineWithoutLessonContentOrQuestions() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {
                  "languages":[{"code":"python","name":"Python","description":"Python path"}],
                  "chapters":[{"code":"python-basics","name":"基础","goal":"基础语法","sequence":1,"prerequisiteChapterCodes":[]}],
                  "learnUnits":[
                    {"languageCode":"python","chapterCode":"python-basics","code":"python.basics","name":"基础","description":"基础语法",
                     "sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,"minCodingScore":70,
                     "learningObjectives":["掌握基础"],"keyConcepts":["变量"]}
                  ]
                }
                """));

        CurriculumGenerator.GeneratedOutline result = generator.generateOutline("python", "learn backend APIs");

        assertEquals(List.of("python.basics"), result.learnUnits().stream().map(LearnUnit::code).toList());
        assertTrue(result.learnUnits().get(0).lessonIntro().isBlank());
        assertTrue(result.learnUnits().get(0).examples().isEmpty());
    }

    @Test
    void generatesDetailedContentForAnOutline() {
        LearnUnit outline = new LearnUnit(
                "unit", "python", "python.basics", "python-basics", "基础", "基础语法", 1, List.of(),
                80, 70, true, List.of("掌握基础"), "", List.of("变量"), List.of(), true);
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"lessonIntro":"从变量开始","learningObjectives":["能定义变量"],
                 "keyConcepts":["变量绑定"],"examples":["name = 'Ada'"]}
                """));

        LearnUnit result = generator.generateContent(outline, "learn backend APIs");

        assertEquals("python.basics", result.code());
        assertEquals("python-basics", result.chapterCode());
        assertEquals("从变量开始", result.lessonIntro());
        assertEquals(List.of("name = 'Ada'"), result.examples());
    }

    @Test
    void rejectsUnknownPrerequisite() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"languages":[{"code":"go","name":"Go","description":"Go path"}],
                 "chapters":[{"code":"go-basics","name":"基础","goal":"基础","sequence":1,"prerequisiteChapterCodes":[]}],
                 "learnUnits":[{"languageCode":"go","chapterCode":"go-basics","code":"go.basics","name":"Basics","description":"Basics",
                 "sequence":1,"passScore":80,"minCodingScore":null,"learningObjectives":["基础"],"keyConcepts":["语法"],
                 "prerequisiteLearnUnitCodes":["go.missing"]}]}
                """));

        assertThrows(IllegalArgumentException.class, () -> generator.generateOutline("go", "learn backend APIs"));
    }

    @Test
    void rejectsMissingRequiredLearnUnitField() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"languages":[{"code":"python","name":"Python","description":"Python path"}],
                 "chapters":[{"code":"python-basics","name":"基础","goal":"基础","sequence":1,"prerequisiteChapterCodes":[]}],
                 "learnUnits":[{"languageCode":"python","chapterCode":"python-basics","code":"python.basics","name":"基础",
                 "description":"基础语法","sequence":1,"prerequisiteLearnUnitCodes":[],"minCodingScore":null,
                 "learningObjectives":["掌握基础"],"keyConcepts":["变量"]}]}
                """));

        assertThrows(IllegalArgumentException.class, () -> generator.generateOutline("python", "learn APIs"));
    }

    private Model model(String response) {
        Model model = mock(Model.class);
        when(model.stream(anyList(), anyList(), argThat((GenerateOptions options) ->
                options != null && options.getResponseFormat() != null
                        && "json_object".equals(options.getResponseFormat().getType())))).thenReturn(
                Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(response).build()))
                        .build()));
        return model;
    }
}
