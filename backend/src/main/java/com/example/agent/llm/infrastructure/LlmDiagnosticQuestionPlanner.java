package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.CodingRubric;
import com.example.agent.learning.assessment.MultipleChoiceConfig;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionOption;
import com.example.agent.learning.assessment.QuestionRole;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.llm.contract.LlmContracts;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 使用 AgentScope Model 按需规划诊断或 LearnUnit 评估题集。
 *
 * <p>模型可以选择活动题目，也可以提出新题；但已有题目只能返回 ID，不能
 * 携带修改后的字段。最终题集仍由 AssessmentService 的 Java 规则校验并写入 SQLite。</p>
 */
@Component
public final class LlmDiagnosticQuestionPlanner implements DiagnosticQuestionPlanner {

    private final Model model;
    private final ObjectMapper mapper;

    public LlmDiagnosticQuestionPlanner(Model model) {
        this(model, new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    @Autowired
    public LlmDiagnosticQuestionPlanner(Model model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
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
        return plan(language, learnUnits, availableQuestions, profile, ignored -> {
        });
    }

    @Override
    public List<Question> plan(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> availableQuestions,
            LearnerProfile profile,
            Consumer<String> onText) {
        Map<String, Question> existing = new HashMap<>();
        for (Question question : availableQuestions) existing.put(question.id(), question);
        Map<String, LearnUnit> learnUnitsByCode = new HashMap<>();
        for (LearnUnit learnUnit : learnUnits) learnUnitsByCode.put(learnUnit.code(), learnUnit);
        String prompt = """
                Choose an assessment question set for a programming learner. Return JSON only:
                {"questions":[...]}.
                The existing catalog may be empty. For an existing question return exactly
                {"existingQuestionId":"..."} using one of the catalog ids.
                You may also create a new question with learnUnitCode, type (MULTIPLE_CHOICE or CODING), difficulty as a JSON integer from 1 to 5 (never a string or label). For CODING questions, rubric must be exactly {"correctness": integer 0..100, "languageUsage": integer 0..100, "clarity": integer 0..100}, never a prose string,
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
            text = AgentScopeTextGenerator.generate(model, prompt, onText);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Assessment question planner is unavailable", error);
        }
        try {
            LlmContracts.DiagnosticPlan root = mapper.readValue(extractJson(text), LlmContracts.DiagnosticPlan.class);
            List<LlmContracts.Question> nodes = root.questions();
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalArgumentException("questions must be a non-empty array");
            }
            List<Question> result = new ArrayList<>();
            for (LlmContracts.Question node : nodes) result.add(parseQuestion(node, existing, learnUnitsByCode));
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
            LlmContracts.Question node, Map<String, Question> existing, Map<String, LearnUnit> learnUnitsByCode) {
        if (node.existingQuestionId() != null) {
            if (!isExistingReference(node)) {
                throw new IllegalArgumentException("Existing question references cannot contain edits");
            }
            Question question = existing.get(node.existingQuestionId());
            if (question == null) throw new IllegalArgumentException("Unknown existing question");
            return question;
        }
        String learnUnitCode = requiredText(node.learnUnitCode(), "learnUnitCode");
        LearnUnit learnUnit = learnUnitsByCode.get(learnUnitCode);
        if (learnUnit == null) throw new IllegalArgumentException("Unknown assessment LearnUnit");
        QuestionType type = node.type();
        if (type == null) throw new IllegalArgumentException("Missing type");
        String prompt = requiredText(node.prompt(), "prompt");
        int difficulty = requiredInt(node.difficulty(), "difficulty", 1, 5);
        int points = requiredInt(node.points(), "points", 1, 1000);
        MultipleChoiceConfig config = null;
        CodingRubric rubric = null;
        if (type == QuestionType.MULTIPLE_CHOICE) {
            if (node.options() == null || node.correctOptionIds() == null || node.multiple() == null) {
                throw new IllegalArgumentException("Multiple choice question needs options, correctOptionIds and multiple");
            }
            List<QuestionOption> options = node.options().stream()
                    .map(option -> new QuestionOption(requiredText(option.id(), "option.id"), requiredText(option.text(), "option.text")))
                    .toList();
            config = new MultipleChoiceConfig(options, strings(node.correctOptionIds(), "correctOptionIds"), node.multiple());
        } else {
            if (node.rubric() == null) throw new IllegalArgumentException("Coding question rubric is required");
            rubric = new CodingRubric(node.rubric().correctness(), node.rubric().languageUsage(), node.rubric().clarity());
        }
        Question question = new Question(
                "generated-question-" + UUID.randomUUID(), learnUnitCode, type, difficulty,
                prompt, points, config, rubric, nullableText(node.language()), nullableText(node.starterCode()),
                strings(node.referenceConcepts(), "referenceConcepts"),
                true, QuestionRole.DIAGNOSTIC);
        QuestionStructureValidator.validate(question, learnUnit);
        return question;
    }

    private String requiredText(String raw, String field) {
        String value = nullableText(raw);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private int requiredInt(Integer value, String field, int min, int max) {
        if (value == null) throw new IllegalArgumentException("Missing or invalid " + field);
        int result = value;
        if (result < min || result > max) throw new IllegalArgumentException("Invalid " + field);
        return result;
    }

    private String nullableText(String raw) {
        return raw == null ? null : raw.trim();
    }

    private List<String> strings(List<String> values, String field) {
        if (values == null) throw new IllegalArgumentException("Missing " + field);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException(field + " must contain non-empty strings");
            result.add(value.trim());
        }
        return result;
    }

    private boolean isExistingReference(LlmContracts.Question node) {
        return node.learnUnitCode() == null && node.type() == null && node.difficulty() == null
                && node.prompt() == null && node.points() == null && node.options() == null
                && node.correctOptionIds() == null && node.multiple() == null && node.language() == null
                && node.starterCode() == null && node.rubric() == null && node.referenceConcepts() == null;
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
