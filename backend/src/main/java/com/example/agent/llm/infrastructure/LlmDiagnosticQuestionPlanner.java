package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 使用现有 Spring AI ChatModel 按需规划诊断或 LearnUnit 评估题集的适配器。
 *
 * <p>模型可以选择活动题目，也可以提出新题；但已有题目只能返回 ID，不能
 * 携带修改后的字段。最终题集仍由 AssessmentService 的 Java 规则校验并写入 SQLite。</p>
 */
@Component
public final class LlmDiagnosticQuestionPlanner implements DiagnosticQuestionPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ChatModel chatModel;

    public LlmDiagnosticQuestionPlanner(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public List<Question> plan(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> availableQuestions,
            LearnerProfile profile) {
        Map<String, Question> existing = new HashMap<>();
        for (Question question : availableQuestions) existing.put(question.id(), question);
        Set<String> learnUnitCodes = new HashSet<>();
        for (LearnUnit learnUnit : learnUnits) learnUnitCodes.add(learnUnit.code());
        String prompt = """
                Choose an assessment question set for a programming learner. Return JSON only:
                {"questions":[...]}.
                The existing catalog may be empty. For an existing question return exactly
                {"existingQuestionId":"..."} using one of the catalog ids.
                You may also create a new question with learnUnitCode, type (MULTIPLE_CHOICE or CODING), prompt,
                points, language, starterCode, rubric, referenceConcepts, and for multiple choice an options array
                of {"id":"A","text":"..."} plus correctOptionIds. New questions must assess the listed LearnUnits.
                Never return changed fields alongside existingQuestionId. Do not return scores or passed decisions.

                Language: %s
                Learner profile: %s
                LearnUnits: %s
                Existing catalog questions: %s
                """.formatted(language.code(), profile, learnUnits, availableQuestions);
        String text;
        try {
            text = chatModel.call(new Prompt(new UserMessage(prompt))).getResult().getOutput().getText();
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
            for (JsonNode node : nodes) result.add(parseQuestion(node, existing, learnUnitCodes));
            return result;
        } catch (Exception error) {
            throw new IllegalArgumentException("Assessment question planner returned invalid JSON", error);
        }
    }

    private Question parseQuestion(JsonNode node, Map<String, Question> existing, Set<String> learnUnitCodes) {
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
        if (!learnUnitCodes.contains(learnUnitCode)) throw new IllegalArgumentException("Unknown assessment LearnUnit");
        QuestionType type = QuestionType.valueOf(requiredText(node, "type"));
        String prompt = requiredText(node, "prompt");
        int points = node.path("points").asInt(type == QuestionType.CODING ? 100 : 20);
        if (points <= 0) throw new IllegalArgumentException("New question points must be positive");
        String config = null;
        String rubric = null;
        if (type == QuestionType.MULTIPLE_CHOICE) {
            JsonNode options = node.get("options");
            JsonNode correct = node.get("correctOptionIds");
            if (options == null || !options.isArray() || correct == null || !correct.isArray()) {
                throw new IllegalArgumentException("Multiple choice question needs options and correctOptionIds");
            }
            ObjectNode configNode = MAPPER.createObjectNode();
            configNode.set("options", options);
            configNode.set("correctOptionIds", correct);
            configNode.put("multiple", node.path("multiple").asBoolean(false));
            config = configNode.toString();
        } else {
            JsonNode rubricNode = node.get("rubric");
            rubric = rubricNode == null || rubricNode.isNull()
                    ? "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}"
                    : rubricNode.toString();
        }
        JsonNode concepts = node.get("referenceConcepts");
        return new Question(
                "generated-question-" + UUID.randomUUID(), learnUnitCode, type, node.path("difficulty").asInt(2),
                prompt, points, config, rubric, nullableText(node, "language"), nullableText(node, "starterCode"),
                concepts == null || concepts.isNull() ? "[]" : concepts.toString(), true);
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
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
