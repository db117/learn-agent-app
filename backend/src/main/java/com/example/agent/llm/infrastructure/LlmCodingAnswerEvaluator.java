package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.CodingAnswerEvaluator;
import com.example.agent.learning.assessment.CodingEvaluationResult;
import com.example.agent.learning.assessment.CodingQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 AgentScope Model 的 Coding 评分实现。
 *
 * <p>它只负责把模型输出转换成领域层的 {@link CodingEvaluationResult}；边界校验和失败回退由领域服务处理。
 */
@Component
public final class LlmCodingAnswerEvaluator implements CodingAnswerEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "correctness", "languageUsage", "clarity", "feedback", "issues");
    private final Model model;

    public LlmCodingAnswerEvaluator(Model model) {
        this.model = model;
    }

    @Override
    public CodingEvaluationResult evaluate(CodingQuestion question, String submittedCode) {
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
                question.language() == null ? "programming" : question.language(), question.prompt(), question.referenceConceptsJson(), question.rubricJson(),
                question.starterCode(), submittedCode == null ? "" : submittedCode);
        String text = AgentScopeTextGenerator.generate(model, prompt);
        try {
            JsonNode root = MAPPER.readTree(extractJson(text));
            if (root == null || !root.isObject()) throw new IllegalArgumentException("response must be an object");
            root.fieldNames().forEachRemaining(field -> {
                if (!ALLOWED_FIELDS.contains(field)) throw new IllegalArgumentException("unsupported evaluator field: " + field);
            });
            int correctness = requiredInt(root, "correctness", 0, 60);
            int languageUsage = requiredInt(root, "languageUsage", 0, 20);
            int clarity = requiredInt(root, "clarity", 0, 20);
            JsonNode feedbackNode = root.get("feedback");
            if (feedbackNode == null || !feedbackNode.isTextual()) throw new IllegalArgumentException("missing feedback");
            List<String> issues = new ArrayList<>();
            JsonNode issuesNode = root.get("issues");
            if (issuesNode == null || !issuesNode.isArray()) throw new IllegalArgumentException("issues must be an array");
            for (JsonNode issue : issuesNode) {
                if (!issue.isTextual()) throw new IllegalArgumentException("issues must contain strings");
                issues.add(issue.textValue());
            }
            return new CodingEvaluationResult(correctness, languageUsage, clarity, feedbackNode.textValue(), issues);
        } catch (Exception error) {
            throw new IllegalArgumentException("Coding evaluator returned invalid JSON", error);
        }
    }

    private int requiredInt(JsonNode root, String field, int min, int max) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException("missing or non-integer " + field);
        int number = value.intValue();
        if (number < min || number > max) throw new IllegalArgumentException(field + " is outside its rubric range");
        return number;
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
