package com.example.agent.llm;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.llm.infrastructure.LlmDiagnosticQuestionPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmDiagnosticQuestionPlannerTest {

    @Test
    void rejectsCodingQuestionWithoutRubricInsteadOfInventingOne() {
        ChatModel model = mock(ChatModel.class);
        String response = """
                {"questions":[{"learnUnitCode":"python.basics","type":"CODING","difficulty":2,
                "prompt":"实现函数","points":100,"language":"python"}]}
                """;
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
        LearnUnit learnUnit = new LearnUnit(
                "unit", "python", "python.basics", "基础", "基础", 1, List.of(), 80, 70,
                true, List.of("目标"), "介绍", List.of("概念"), List.of("示例"), true);

        assertThrows(IllegalArgumentException.class, () -> new LlmDiagnosticQuestionPlanner(model).plan(
                new LearningLanguage("language", "python", "Python", "path", true),
                List.of(learnUnit), List.<Question>of(),
                new LearnerProfile("journey", "Java", 1, "", "learn Python")));
    }
}
