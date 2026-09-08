package com.example.agent.llm;

import com.example.agent.learning.assessment.CodingQuestion;
import com.example.agent.llm.infrastructure.LlmCodingAnswerEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private ChatModel model(String response) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
        return model;
    }
}
