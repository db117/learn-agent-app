package com.db117.learnagent.learning.application;

import com.db117.learnagent.agent.runtime.TutorModel;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

/** 进入 LearnUnit 时生成 Concept、Example 和 Practice，并返回可保存的内容快照。 */
@ApplicationScoped
public class LearnUnitContentGenerator {
    private static final String SYSTEM_PROMPT = """
            任务标识：LEARN_UNIT_CONTENT_GENERATION。
            你是 Learn Mode 的课程内容生成器。
            只能根据给定 LearnUnit 大纲生成当前单元内容，只返回一个 JSON 对象，不要 Markdown、解释或额外文字。
            JSON 格式必须是：
            {"concept":"Concept 讲解","example":"Example 示例或代码","practice":"Practice 练习要求"}
            Concept 要解释目标涉及的核心概念；Example 要给出最小可理解示例；Practice 必须是可验证的具体练习。
            不要生成下一个单元的内容，不要声称已经完成练习，不要修改学习进度。
            """;
    private static final int MAX_CONTENT_LENGTH = 12_000;
    private final Model model;
    private final ObjectMapper objectMapper;

    @Inject
    public LearnUnitContentGenerator(TutorModel tutorModel) {
        this(tutorModel.model());
    }

    LearnUnitContentGenerator(Model model) {
        this.model = model;
        this.objectMapper = new ObjectMapper();
    }

    public String generate(LearnUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        if (model == null) {
            throw LearningRequestException.internal(
                    "MODEL_UNAVAILABLE", "Tutor 模型尚未配置，暂时无法生成学习内容");
        }
        try {
            var responses = model.stream(
                            List.of(
                                    new SystemMessage(SYSTEM_PROMPT),
                                    new UserMessage("learn-unit-content-generator", prompt(unit))),
                            List.of(),
                            GenerateOptions.builder().temperature(0.2).maxTokens(1200).build())
                    .collectList()
                    .block();
            var response = responses == null ? "" : responses.stream()
                    .flatMap(value -> textBlocks(value).stream())
                    .map(TextBlock::getText)
                    .filter(Objects::nonNull)
                    .reduce("", String::concat);
            return parse(response);
        } catch (LearningRequestException error) {
            throw error;
        } catch (RuntimeException error) {
            throw LearningRequestException.internal(
                    "LEARNING_CONTENT_GENERATION_FAILED", "暂时无法生成当前单元内容，请稍后重试");
        }
    }

    private String parse(String response) {
        try {
            var root = objectMapper.readTree(jsonObject(response));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("learning content must be a JSON object");
            }
            var concept = requiredText(root, "concept");
            var example = requiredText(root, "example");
            var practice = requiredText(root, "practice");
            return """
                    ## Concept
                    %s
                    
                    ## Example
                    %s
                    
                    ## Practice
                    %s
                    """.formatted(concept, example, practice).strip();
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw LearningRequestException.internal(
                    "LEARNING_CONTENT_GENERATION_FAILED", "暂时无法生成当前单元内容，请稍后重试");
        }
    }

    private String prompt(LearnUnit unit) {
        return """
                请只根据以下 LearnUnit 大纲生成内容：
                <title>%s</title>
                <objective>%s</objective>
                """.formatted(unit.title(), unit.objective());
    }

    private static List<TextBlock> textBlocks(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return List.of();
        }
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .toList();
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()
                || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("learning content field must be text: " + field);
        }
        return node.get(field).asText().trim();
    }

    private static String jsonObject(String response) {
        var text = response == null ? "" : response.strip();
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH);
        }
        if (text.startsWith("```") && text.endsWith("```")) {
            var firstLineEnd = text.indexOf('\n');
            text = firstLineEnd < 0 ? "" : text.substring(firstLineEnd + 1, text.length() - 3).strip();
        }
        var start = text.indexOf('{');
        var end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("learning content does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
