package com.example.agent.llm;

import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.llm.infrastructure.LlmCurriculumGenerator;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionRole;
import com.example.agent.learning.assessment.QuestionType;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void generatesBoundedStructuredContentAndIndependentQuestionsForAnOutline() {
        LearnUnit outline = new LearnUnit(
                "unit", "python", "python.basics", "python-basics", "基础", "基础语法", 1, List.of(),
                80, 70, true, List.of("掌握基础"), "", List.of("变量"), List.of(), true);
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"ability":"定义并读取变量","estimatedMinutes":10,"lessonIntro":"从变量开始",
                 "examples":["name = 'Ada'"],"guidedPracticePrompt":"定义一个保存姓名的变量",
                 "guidedPracticeHints":["使用赋值语句"],"independentCheckPrompt":"完成变量检查",
                 "questions":[{"type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"哪个是变量？","points":20,
                   "options":[{"id":"A","text":"name"},{"id":"B","text":"123"}],
                   "correctOptionIds":["A"],"multiple":false,"referenceConcepts":["变量"]}]}
                """));

        CurriculumGenerator.GeneratedLearnUnitContent result = generator.generateContent(outline, "learn backend APIs");

        assertEquals("python.basics", result.learnUnit().code());
        assertEquals(QuestionRole.INDEPENDENT, result.independentQuestions().get(0).role());
        assertEquals("python-basics", result.learnUnit().chapterCode());
        assertEquals("定义并读取变量", result.learnUnit().ability());
        assertEquals(10, result.learnUnit().estimatedMinutes());
        assertEquals("从变量开始", result.learnUnit().lessonIntro());
        assertEquals("定义一个保存姓名的变量", result.learnUnit().guidedPracticePrompt());
        assertEquals("完成变量检查", result.learnUnit().independentCheckPrompt());
        assertEquals(List.of("name = 'Ada'"), result.learnUnit().examples());
        assertEquals("[\"变量\"]", result.independentQuestions().get(0).referenceConceptsJson());
        assertEquals(QuestionType.MULTIPLE_CHOICE, result.independentQuestions().get(0).type());
    }

    @Test
    void contentRequestUsesStrictJsonSchema() {
        LearnUnit outline = new LearnUnit(
                "unit", "typescript", "typescript.arrays", "typescript-basics", "数组", "数组基础", 1, List.of(),
                80, 70, true, List.of("掌握数组"), "", List.of("T[]"), List.of(), true);
        Model model = model("""
                {"ability":"声明数组","estimatedMinutes":10,"lessonIntro":"介绍数组",
                 "examples":["const values: number[] = [1, 2];"],"guidedPracticePrompt":"声明数组",
                 "guidedPracticeHints":[],"independentCheckPrompt":"完成数组题",
                 "questions":[{"type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"哪一个是数组？","points":20,
                   "options":[{"id":"A","text":"number[]"},{"id":"B","text":"number"}],
                   "correctOptionIds":["A"],"multiple":false,"language":null,"starterCode":null,"rubric":null,"referenceConcepts":["T[]"]}]}
                """);
        new LlmCurriculumGenerator(model).generateContent(outline, "context");

        ArgumentCaptor<GenerateOptions> options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model).stream(anyList(), anyList(), options.capture());
        assertEquals("json_schema", options.getValue().getResponseFormat().getType());
        assertEquals("learn_unit_content", options.getValue().getResponseFormat().getJsonSchema().getName());
        assertEquals(true, options.getValue().getResponseFormat().getJsonSchema().getStrict());
        assertTrue(options.getValue().getResponseFormat().getJsonSchema().getSchema().toString().contains("rubric"));
        assertTrue(options.getValue().getResponseFormat().getJsonSchema().getSchema().toString().contains("maxItems"));
    }

    @Test
    void reportsCodingValidationReasonInsteadOfCallingItInvalidJson() {
        LearnUnit outline = new LearnUnit(
                "unit", "typescript", "typescript.arrays", "typescript-basics", "数组", "数组基础", 1, List.of(),
                80, 70, true, List.of("掌握数组"), "", List.of("T[]"), List.of(), true);
        LlmCurriculumGenerator generator = new LlmCurriculumGenerator(model("""
                {"ability":"声明数组","estimatedMinutes":10,"lessonIntro":"介绍数组",
                 "examples":["const values: number[] = [1, 2];"],"guidedPracticePrompt":"声明数组",
                 "guidedPracticeHints":[],"independentCheckPrompt":"完成数组题",
                 "questions":[{"type":"CODING","difficulty":2,"prompt":"声明一个数组","points":100,
                   "options":[],"correctOptionIds":[],"multiple":false,"referenceConcepts":["T[]"]}]}
                """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> generator.generateContent(outline, "context"));

        assertTrue(error.getMessage().contains("Coding question rubric is required"), error.getMessage());
    }

    @Test
    void rejectsMultipleAbilitiesAndInvalidDuration() {
        LearnUnit outline = new LearnUnit(
                "unit", "python", "python.basics", "python-basics", "基础", "基础语法", 1, List.of(),
                80, 70, true, List.of("掌握基础"), "", List.of("变量"), List.of(), true);
        LlmCurriculumGenerator multiple = new LlmCurriculumGenerator(model("""
                {"abilities":["定义变量","读取变量"],"estimatedMinutes":10,"lessonIntro":"介绍",
                 "examples":["x = 1"],"guidedPracticePrompt":"练习","guidedPracticeHints":[],
                 "independentCheckPrompt":"检查","questions":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> multiple.generateContent(outline, "context"));

        LlmCurriculumGenerator invalidDuration = new LlmCurriculumGenerator(model("""
                {"ability":"定义变量","estimatedMinutes":0,"lessonIntro":"介绍",
                 "examples":["x = 1"],"guidedPracticePrompt":"练习","guidedPracticeHints":[],
                 "independentCheckPrompt":"检查","questions":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> invalidDuration.generateContent(outline, "context"));
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
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class))).thenReturn(
                Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(response).build()))
                        .build()));
        return model;
    }
}
