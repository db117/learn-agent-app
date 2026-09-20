package com.db117.learnagent.learning.application;

import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.agent.runtime.TutorModel;
import com.fasterxml.jackson.core.JsonProcessingException;
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

/** 生成可供学习者确认的规划草稿；不保存 Learning Domain。 */
@ApplicationScoped
public class LearningOutlineGenerator {
    private static final String SYSTEM_PROMPT = """
            任务标识：LEARNING_OUTLINE_GENERATION。
            你是 Learning Journey 规划器。根据学习目标和学习者背景生成一个可调整的 JSON 大纲草稿。
            只能返回一个 JSON 对象，不要 Markdown、解释或额外文字。
            JSON 格式必须是：
            {"chapters":[{"code":"basics","title":"章节标题","units":[{"code":"intro","title":"单元标题","objective":"可验证目标"}]}]}
            chapters 至少一个；每个 chapter 至少一个 unit；code 使用唯一的小写短横线编码。
            只生成标题和目标，不生成 Concept、Example、Practice 内容，不声称已保存或完成学习路径。
            """;
    private static final int MAX_RESPONSE_LENGTH = 20_000;
    private final Model model;
    private final ObjectMapper objectMapper;

    @Inject
    public LearningOutlineGenerator(TutorModel tutorModel) {
        this(tutorModel.model());
    }

    LearningOutlineGenerator(Model model) {
        this.model = model;
        this.objectMapper = new ObjectMapper();
    }

    public String generate(TutorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (model == null) {
            throw LearningRequestException.internal(
                    "MODEL_UNAVAILABLE", "Tutor 模型尚未配置，暂时无法生成学习大纲");
        }
        try {
            var responses = model.stream(
                            List.of(
                                    new SystemMessage(SYSTEM_PROMPT),
                                    new UserMessage("learning-outline-generator", prompt(context))),
                            List.of(),
                            GenerateOptions.builder().temperature(0.2).maxTokens(1_600).build())
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
                    "LEARNING_OUTLINE_GENERATION_FAILED", "暂时无法生成有效的学习大纲，请稍后重试");
        }
    }

    private String prompt(TutorContext context) {
        return """
                <goal>%s</goal>
                <learner-background>%s</learner-background>
                """.formatted(context.journeyGoalDescription(), context.learnerBackgroundSummary());
    }

    private String parse(String response) {
        try {
            var root = objectMapper.readTree(jsonObject(response));
            if (root == null || !root.isObject()
                    || !root.has("chapters") || !root.get("chapters").isArray()
                    || root.get("chapters").isEmpty()) {
                throw new IllegalArgumentException("outline must contain chapters");
            }
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw LearningRequestException.internal(
                    "LEARNING_OUTLINE_GENERATION_FAILED", "暂时无法生成有效的学习大纲，请稍后重试");
        }
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

    private static String jsonObject(String response) {
        var text = response == null ? "" : response.strip();
        if (text.length() > MAX_RESPONSE_LENGTH) {
            text = text.substring(0, MAX_RESPONSE_LENGTH);
        }
        if (text.startsWith("```") && text.endsWith("```")) {
            var firstLineEnd = text.indexOf('\n');
            text = firstLineEnd < 0 ? "" : text.substring(firstLineEnd + 1, text.length() - 3).strip();
        }
        var start = text.indexOf('{');
        var end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("outline response does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
