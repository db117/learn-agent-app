package com.example.agent.llm;

import com.example.agent.learning.assessment.CodingQuestion;
import com.example.agent.llm.infrastructure.LlmCodingAnswerEvaluator;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmCodingAnswerEvaluatorTest {

    private final CodingQuestion question = new CodingQuestion(
            "question", "typescript.generics", "Write a generic identity function.", "typescript",
            "", "{\"correctness\":60}", "[\"generics\"]", 100);

    @Test
    void validatesDimensionsAndCalculatesTotal() {
        var evaluator = new LlmCodingAnswerEvaluator(model("""
                {"correctness":55,"languageUsage":18,"clarity":17,"feedback":"Good work","issues":["Add a constraint"]}
                """));

        var result = evaluator.evaluate(question, "const identity = <T>(value: T): T => value;");

        assertEquals(90, result.totalScore());
        assertEquals(List.of("Add a constraint"), result.issues());
    }

    @Test
    void rejectsOutOfRangeAndMissingFields() {
        var outOfRange = new LlmCodingAnswerEvaluator(model("""
                {"correctness":61,"languageUsage":18,"clarity":17,"feedback":"bad","issues":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> outOfRange.evaluate(question, "code"));

        var missingFeedback = new LlmCodingAnswerEvaluator(model("""
                {"correctness":60,"languageUsage":20,"clarity":20,"issues":[]}
                """));
        assertThrows(IllegalArgumentException.class, () -> missingFeedback.evaluate(question, "code"));

        var decisionField = new LlmCodingAnswerEvaluator(model("""
                {"correctness":60,"languageUsage":20,"clarity":20,"feedback":"bad","issues":[],"passed":true}
                """));
        assertThrows(IllegalArgumentException.class, () -> decisionField.evaluate(question, "code"));
    }

    @Test
    void rejectsMalformedJsonResponse() {
        var evaluator = new LlmCodingAnswerEvaluator(model("not json"));

        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(question, "code"));
    }

    @Test
    void rejectsBlankRubricIssue() {
        var evaluator = new LlmCodingAnswerEvaluator(model("""
                {"correctness":60,"languageUsage":20,"clarity":20,"feedback":"bad","issues":[""]}
                """));

        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(question, "code"));
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
