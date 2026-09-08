package com.example.agent.llm;

import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.llm.infrastructure.LlmCurriculumGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
                  ]
                }
                """));

        CurriculumGenerator.GeneratedCurriculum result = generator.generate("python", "learn backend APIs");

        assertEquals(List.of("python"), result.languages().stream().map(value -> value.code()).toList());
        assertEquals(List.of("python.basics", "python.collections"),
                result.learnUnits().stream().map(value -> value.code()).toList());
        assertNotEquals("python.basics", result.learnUnits().get(0).id());
        assertEquals(List.of("python.basics"), result.learnUnits().get(1).prerequisiteLearnUnitCodes());
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

    private ChatModel model(String response) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
        return model;
    }
}
