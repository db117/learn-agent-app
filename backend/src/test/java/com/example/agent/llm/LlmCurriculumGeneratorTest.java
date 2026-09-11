package com.example.agent.llm;

import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.llm.infrastructure.LlmCurriculumGenerator;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmCurriculumGeneratorTest {

    @Test
    void parsesGeneratedLanguagesLearnUnitsAndAssignsServerIds() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {
                  "languages":[{"code":"python","name":"Python","description":"Python path"}],
                  "learnUnits":[
                    {"languageCode":"python","code":"python.basics","name":"基础","description":"基础语法",
                     "sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,"minCodingScore":70,
                     "learningObjectives":["掌握基础"],"lessonIntro":"开始","keyConcepts":["变量"],"examples":["x = 1"]},
                    {"languageCode":"python","code":"python.collections","name":"集合","description":"集合类型",
                     "sequence":2,"prerequisiteLearnUnitCodes":["python.basics"],"passScore":85,"minCodingScore":75,
                     "learningObjectives":["使用集合"],"lessonIntro":"继续","keyConcepts":["list"],"examples":["items = []"]}
                  ],
                  "questions":[
                    {"learnUnitCode":"python.basics","type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"基础题","points":20,
                     "options":[{"id":"A","text":"对"},{"id":"B","text":"错"}],"correctOptionIds":["A"],"multiple":false,
                     "referenceConcepts":["变量"]},
                    {"learnUnitCode":"python.basics","type":"CODING","difficulty":2,"prompt":"基础编码题","points":100,
                     "language":"python","starterCode":"","rubric":{"correctness":60,"clarity":40},"referenceConcepts":["变量"]},
                    {"learnUnitCode":"python.collections","type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"集合题","points":20,
                     "options":[{"id":"A","text":"list"},{"id":"B","text":"tuple"}],"correctOptionIds":["A"],"multiple":false,
                     "referenceConcepts":["list"]},
                    {"learnUnitCode":"python.collections","type":"CODING","difficulty":2,"prompt":"集合编码题","points":100,
                     "language":"python","starterCode":"","rubric":{"correctness":60,"clarity":40},"referenceConcepts":["list"]}
                  ]
                }
                """));

        CurriculumGenerator.GeneratedCurriculum result = generator.generate("python", "learn backend APIs");

        assertEquals(List.of("python"), result.languages().stream().map(value -> value.code()).toList());
        assertEquals(List.of("python.basics", "python.collections"),
                result.learnUnits().stream().map(value -> value.code()).toList());
        assertNotEquals("python.basics", result.learnUnits().get(0).id());
        assertEquals(List.of("python.basics"), result.learnUnits().get(1).prerequisiteLearnUnitCodes());
        assertEquals(4, result.questions().size());
    }

    @Test
    void rejectsUnknownPrerequisite() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"languages":[{"code":"go","name":"Go","description":"Go path"}],
                 "learnUnits":[{"languageCode":"go","code":"go.basics","name":"Basics","description":"Basics",
                 "prerequisiteLearnUnitCodes":["go.missing"]}]}
                """));

        assertThrows(IllegalArgumentException.class, () -> generator.generate("go", "learn backend APIs"));
    }

    @Test
    void rejectsMissingRequiredLearnUnitField() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {
                  "languages":[{"code":"python","name":"Python","description":"Python path"}],
                  "learnUnits":[{"languageCode":"python","code":"python.basics","name":"基础",
                    "description":"基础语法","sequence":1,"prerequisiteLearnUnitCodes":[],
                    "minCodingScore":null,"learningObjectives":["掌握基础"],"lessonIntro":"开始",
                    "keyConcepts":["变量"],"examples":["x = 1"]}],
                  "questions":[{"learnUnitCode":"python.basics","type":"MULTIPLE_CHOICE","difficulty":1,
                    "prompt":"基础题","points":20,"options":[{"id":"A","text":"对"},{"id":"B","text":"错"}],
                    "correctOptionIds":["A"],"multiple":false}]
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> generator.generate("python", "learn APIs"));
    }

    @Test
    void acceptsAChoiceOnlyCurriculumAndDoesNotInventCodingQuestions() {
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {
                  "languages":[{"code":"spanish","name":"Spanish","description":"Reading path"}],
                  "learnUnits":[{"languageCode":"spanish","code":"spanish.reading","name":"阅读",
                    "description":"阅读技术文档","sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,
                    "minCodingScore":null,"learningObjectives":["读懂文档"],"lessonIntro":"从文档开始",
                    "keyConcepts":["词汇"],"examples":["API reference"]}],
                  "questions":[{"learnUnitCode":"spanish.reading","type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"词汇题",
                    "points":20,"options":[{"id":"A","text":"正确"},{"id":"B","text":"错误"}],
                    "correctOptionIds":["A"],"multiple":false,"referenceConcepts":["词汇"]}]
                }
                """));

        CurriculumGenerator.GeneratedCurriculum result = generator.generate("spanish", "read docs");

        assertEquals(1, result.questions().size());
        assertEquals("MULTIPLE_CHOICE", result.questions().get(0).type().name());
    }

    @Test
    void doesNotCapCurriculumAtEightUnits() {
        String units = IntStream.rangeClosed(1, 9)
                .mapToObj(index -> """
                        {"languageCode":"python","code":"python.unit-%d","name":"单元%d","description":"内容%d",
                         "sequence":%d,"passScore":80,"prerequisiteLearnUnitCodes":[],"minCodingScore":null,
                         "learningObjectives":["目标%d"],"lessonIntro":"介绍%d","keyConcepts":["概念%d"],"examples":["示例%d"]}
                        """.formatted(index, index, index, index, index, index, index, index))
                .collect(Collectors.joining(","));
        String questions = IntStream.rangeClosed(1, 9)
                .mapToObj(index -> """
                        {"learnUnitCode":"python.unit-%d","type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"问题%d",
                         "points":20,"options":[{"id":"A","text":"yes"},{"id":"B","text":"no"}],
                         "correctOptionIds":["A"],"multiple":false}
                        """.formatted(index, index))
                .collect(Collectors.joining(","));
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"languages":[{"code":"python","name":"Python","description":"path"}],
                 "learnUnits":[%s],"questions":[%s]}
                """.formatted(units, questions)));

        CurriculumGenerator.GeneratedCurriculum result = generator.generate("python", "learn APIs");

        assertEquals(9, result.learnUnits().size());
        assertEquals(9, result.questions().size());
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
