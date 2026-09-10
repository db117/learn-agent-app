package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 使用 AgentScope Model 按需规划诊断或 LearnUnit 评估题集。
 *
 * <p>模型可以选择活动题目，也可以提出新题；但已有题目只能返回 ID，不能
 * 携带修改后的字段。最终题集仍由 AssessmentService 的 Java 规则校验并写入 SQLite。</p>
 */
@Component
public final class LlmDiagnosticQuestionPlanner implements DiagnosticQuestionPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Model model;

    public LlmDiagnosticQuestionPlanner(Model model) {
        this.model = model;
    }

    /**
     * 请求模型规划评估题集，并将已有题目引用或新题目统一解析为 Question。
     *
     * <p>已有题目只能按 ID 复用，新题目必须经过题型和 LearnUnit 规则校验；模型失败或返回非法 JSON
     * 时直接抛错。</p>
     */
    @Override
    public List<Question> plan(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> availableQuestions,
            LearnerProfile profile) {
        Map<String, Question> existing = new HashMap<>();
        for (Question question : availableQuestions) existing.put(question.id(), question);
        Map<String, LearnUnit> learnUnitsByCode = new HashMap<>();
        for (LearnUnit learnUnit : learnUnits) learnUnitsByCode.put(learnUnit.code(), learnUnit);
        String prompt = """
                Choose an assessment question set for a programming learner. Return JSON only:
                {"questions":[...]}.
                The existing catalog may be empty. For an existing question return exactly
                {"existingQuestionId":"..."} using one of the catalog ids.
                You may also create a new question with learnUnitCode, type (MULTIPLE_CHOICE or CODING), difficulty,
                prompt, points, language, starterCode, rubric, referenceConcepts, and for multiple choice an options array
                of {"id":"A","text":"..."} plus correctOptionIds and boolean multiple. New questions must assess the listed LearnUnits.
                A LearnUnit with null minCodingScore has no coding learning objective and must not receive a CODING question.
                Never return changed fields alongside existingQuestionId. Do not return scores or passed decisions.

                Language: %s
                Learner profile: %s
                LearnUnits: %s
                Existing catalog questions: %s
                For every listed LearnUnit, return at least two distinct diagnostic questions.
                Include at least one MULTIPLE_CHOICE question; when minCodingScore is not null,
                include at least one CODING question as the second evidence item.
                """.formatted(language.code(), profile, learnUnits, availableQuestions);
        String text;
        try {
            text = AgentScopeTextGenerator.generate(model, prompt);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Assessment question planner is unavailable", error);
        }
        try {
            JsonNode root = MAPPER.readTree(extractJson(text));
            JsonNode nodes = root.get("questions");
            if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
                throw new IllegalArgumentException("questions must be a non-empty array");
            }
            List<Question> result = new ArrayList<>();
            for (JsonNode node : nodes) result.add(parseQuestion(node, existing, learnUnitsByCode));
            return result;
        } catch (Exception error) {
            throw new IllegalArgumentException("Assessment question planner returned invalid JSON", error);
        }
    }

    /**
     * 解析一项题目规划结果。
     *
     * <p>结果只有一个 existingQuestionId 时复用现有题目；否则按新题目字段构建对象并执行结构校验。</p>
     */
    private Question parseQuestion(
            JsonNode node, Map<String, Question> existing, Map<String, LearnUnit> learnUnitsByCode) {
        JsonNode existingId = node.get("existingQuestionId");
        if (existingId != null) {
            if (!existingId.isTextual() || node.size() != 1) {
                throw new IllegalArgumentException("Existing question references cannot contain edits");
            }
            Question question = existing.get(existingId.textValue());
            if (question == null) throw new IllegalArgumentException("Unknown existing question");
            return question;
        }
        String learnUnitCode = requiredText(node, "learnUnitCode");
        LearnUnit learnUnit = learnUnitsByCode.get(learnUnitCode);
        if (learnUnit == null) throw new IllegalArgumentException("Unknown assessment LearnUnit");
        QuestionType type = QuestionType.valueOf(requiredText(node, "type").toUpperCase(Locale.ROOT));
        String prompt = requiredText(node, "prompt");
        int difficulty = requiredInt(node, "difficulty", 1, 5);
        int points = requiredInt(node, "points", 1, 1000);
        String config = null;
        String rubric = null;
        if (type == QuestionType.MULTIPLE_CHOICE) {
            JsonNode options = node.get("options");
            JsonNode correct = node.get("correctOptionIds");
            JsonNode multiple = node.get("multiple");
            if (options == null || !options.isArray() || correct == null || !correct.isArray()
                    || multiple == null || !multiple.isBoolean()) {
                throw new IllegalArgumentException("Multiple choice question needs options, correctOptionIds and multiple");
            }
            ObjectNode configNode = MAPPER.createObjectNode();
            configNode.set("options", options);
            configNode.set("correctOptionIds", correct);
            configNode.set("multiple", multiple);
            config = configNode.toString();
        } else {
            JsonNode rubricNode = node.get("rubric");
            if (rubricNode == null || rubricNode.isNull()) throw new IllegalArgumentException("Coding question rubric is required");
            rubric = rubricNode.toString();
        }
        JsonNode concepts = node.get("referenceConcepts");
        Question question = new Question(
                "generated-question-" + UUID.randomUUID(), learnUnitCode, type, difficulty,
                prompt, points, config, rubric, nullableText(node, "language"), nullableText(node, "starterCode"),
                concepts == null || concepts.isNull() ? "[]" : concepts.toString(), true);
        QuestionStructureValidator.validate(question, learnUnit);
        return question;
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private int requiredInt(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException("Missing or invalid " + field);
        int result = value.asInt(Integer.MIN_VALUE);
        if (result < min || result > max) throw new IllegalArgumentException("Invalid " + field);
        return result;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String extractJson(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Empty planner response");
        String trimmed = value.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int newline = trimmed.indexOf('\n');
            trimmed = newline >= 0
                    ? trimmed.substring(newline + 1, trimmed.length() - 3).trim()
                    : trimmed.substring(3, trimmed.length() - 3).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Response is not a JSON object");
        return trimmed.substring(start, end + 1);
    }
}
