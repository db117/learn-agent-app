package com.example.agent.llm;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.llm.infrastructure.LlmDiagnosticQuestionPlanner;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmDiagnosticQuestionPlannerTest {

    @Test
    void rejectsCodingQuestionWithoutRubricInsteadOfInventingOne() {
        Model model = mock(Model.class);
        String response = """
                {"questions":[{"learnUnitCode":"python.basics","type":"CODING","difficulty":2,
                "prompt":"实现函数","points":100,"language":"python"}]}
                """;
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class))).thenReturn(
                Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(response).build()))
                        .build()));
        LearnUnit learnUnit = new LearnUnit(
                "unit", "python", "python.basics", "python-basics", "基础", "基础", 1, List.of(), 80, 70,
                true, List.of("目标"), "介绍", List.of("概念"), List.of("示例"), true);

        assertThrows(IllegalArgumentException.class, () -> new LlmDiagnosticQuestionPlanner(model).plan(
                new LearningLanguage("language", "python", "Python", "path", true),
                List.of(learnUnit), List.<Question>of(),
                new LearnerProfile("journey", "Java", 1, "", "learn Python")));
    }
}
