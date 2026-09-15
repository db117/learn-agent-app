package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.CodingAnswerEvaluator;
import com.example.agent.learning.assessment.CodingEvaluationResult;
import com.example.agent.learning.assessment.CodingQuestion;
import com.example.agent.llm.contract.LlmContracts;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 AgentScope Model 的 Coding 评分实现。
 *
 * <p>它只负责把模型输出转换成领域层的 {@link CodingEvaluationResult}；边界校验和失败回退由领域服务处理。
 */
@Component
public final class LlmCodingAnswerEvaluator implements CodingAnswerEvaluator {

    private final Model model;
    private final ObjectMapper mapper;

    public LlmCodingAnswerEvaluator(Model model) {
        this(model, new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    @Autowired
    public LlmCodingAnswerEvaluator(Model model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public CodingEvaluationResult evaluate(CodingQuestion question, String submittedCode) {
        return evaluate(question, submittedCode, ignored -> {
        });
    }

    @Override
    public CodingEvaluationResult evaluate(
            CodingQuestion question, String submittedCode, Consumer<String> onModelText) {
        String prompt = """
                Evaluate this %s learning answer. Return JSON only, with exactly these fields:
                {"correctness": integer 0..60, "languageUsage": integer 0..20,
                 "clarity": integer 0..20, "feedback": string, "issues": string[]}
                Do not return a passed field. Judge the submitted code against the prompt and rubric.

                Question: %s
                Reference concepts: %s
                Rubric: %s
                Starter code: %s
                Submitted code:
                %s
                """.formatted(
                question.language() == null ? "programming" : question.language(), question.prompt(), json(question.referenceConcepts()), json(question.rubric()),
                question.starterCode(), submittedCode == null ? "" : submittedCode);
        String text = AgentScopeTextGenerator.generate(model, prompt, onModelText);
        try {
            LlmContracts.CodingEvaluation response = mapper.readValue(
                    extractJson(text), LlmContracts.CodingEvaluation.class);
            int correctness = requiredInt(response.correctness(), "correctness", 0, 60);
            int languageUsage = requiredInt(response.languageUsage(), "languageUsage", 0, 20);
            int clarity = requiredInt(response.clarity(), "clarity", 0, 20);
            String feedback = response.feedback();
            if (feedback == null || feedback.isBlank()) throw new IllegalArgumentException("missing feedback");
            List<String> issues = response.issues();
            if (issues == null || issues.stream().anyMatch(issue -> issue == null || issue.isBlank())) {
                throw new IllegalArgumentException("issues must contain strings");
            }
            return new CodingEvaluationResult(correctness, languageUsage, clarity, feedback, issues);
        } catch (Exception error) {
            throw new IllegalArgumentException("Coding evaluator returned invalid JSON", error);
        }
    }

    private int requiredInt(Integer value, String field, int min, int max) {
        if (value == null) throw new IllegalArgumentException("missing or non-integer " + field);
        int number = value;
        if (number < min || number > max) throw new IllegalArgumentException(field + " is outside its rubric range");
        return number;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize coding evaluation prompt", error);
        }
    }

    private String extractJson(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("empty evaluator response");
        String trimmed = value.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int newline = trimmed.indexOf('\n');
            trimmed = newline >= 0 ? trimmed.substring(newline + 1, trimmed.length() - 3).trim() : trimmed.substring(3, trimmed.length() - 3).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("response is not a JSON object");
        return trimmed.substring(start, end + 1);
    }
}
