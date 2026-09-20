package com.db117.learnagent.practice.application;

import com.db117.learnagent.agent.runtime.TutorModel;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.practice.domain.ChoiceOption;
import com.db117.learnagent.practice.domain.ChoiceQuestion;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 使用已配置的 Tutor 模型生成并校验选择题；生成结果在保存前成为 PracticeTask 快照。 */
@ApplicationScoped
public class ChoiceQuestionGenerator {
    private static final String SYSTEM_PROMPT = """
            任务标识：CHOICE_QUESTION_GENERATION。
            你是学习系统的选择题出题器。
            根据给定 LearnUnit 生成一道单选题，必须只返回一个 JSON 对象，不要 Markdown、解释或额外文字。
            JSON 格式必须是：
            {"prompt":"题干","options":[{"id":"a","label":"选项"},{"id":"b","label":"选项"},{"id":"c","label":"选项"},{"id":"d","label":"选项"}],"correctOptionId":"a"}
            必须恰好生成 4 个选项，id 固定使用 a、b、c、d；只有一个选项正确；错误选项要有迷惑性但不能有歧义。
            正确答案必须能由 LearnUnit 的目标和内容直接支持，不得引入内容之外的事实。
            """;
    private static final int MAX_CONTENT_LENGTH = 12_000;
    private final Model model;
    private final ObjectMapper objectMapper;

    @Inject
    public ChoiceQuestionGenerator(TutorModel tutorModel) {
        this(tutorModel.model());
    }

    ChoiceQuestionGenerator(Model model) {
        this.model = model;
        this.objectMapper = new ObjectMapper();
    }

    public ChoiceQuestion generate(LearnUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        if (model == null) {
            throw LearningRequestException.internal(
                    "MODEL_UNAVAILABLE", "Tutor 模型尚未配置，暂时无法生成选择题");
        }
        try {
            var responses = model.stream(
                            List.of(
                                    new SystemMessage(SYSTEM_PROMPT),
                                    new UserMessage("choice-generator", prompt(unit))),
                            List.of(),
                            GenerateOptions.builder().temperature(0.2).maxTokens(800).build())
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
                    "CHOICE_GENERATION_FAILED", "暂时无法生成有效的选择题，请稍后重试");
        }
    }

    private ChoiceQuestion parse(String response) {
        try {
            var root = objectMapper.readTree(jsonObject(response));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("choice response must be a JSON object");
            }
            var optionsNode = root.get("options");
            if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() != 4) {
                throw new IllegalArgumentException("choice response must contain exactly four options");
            }
            var options = new ArrayList<ChoiceOption>();
            for (JsonNode option : optionsNode) {
                options.add(new ChoiceOption(requiredText(option, "id"), requiredText(option, "label")));
            }
            return new ChoiceQuestion(
                    requiredText(root, "prompt"),
                    options,
                    requiredText(root, "correctOptionId"));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw LearningRequestException.internal(
                    "CHOICE_GENERATION_FAILED", "暂时无法生成有效的选择题，请稍后重试");
        }
    }

    private String prompt(LearnUnit unit) {
        var content = unit.content();
        var boundedContent = content.length() > MAX_CONTENT_LENGTH
                ? content.substring(0, MAX_CONTENT_LENGTH)
                : content;
        return """
                请只根据以下 LearnUnit 出题：
                <title>%s</title>
                <objective>%s</objective>
                <content>
                %s
                </content>
                """.formatted(unit.title(), unit.objective(), boundedContent);
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
            throw new IllegalArgumentException("choice response field must be text: " + field);
        }
        return node.get(field).asText().trim();
    }

    private static String jsonObject(String response) {
        var text = response == null ? "" : response.strip();
        if (text.startsWith("```") && text.endsWith("```")) {
            var firstLineEnd = text.indexOf('\n');
            text = firstLineEnd < 0 ? "" : text.substring(firstLineEnd + 1, text.length() - 3).strip();
        }
        var start = text.indexOf('{');
        var end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("choice response does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
