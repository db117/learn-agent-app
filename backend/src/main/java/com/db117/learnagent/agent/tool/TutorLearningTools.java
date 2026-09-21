package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** TutorAgent 的学习内容领域工具；只校验并保存内容快照，不调用模型。 */
@Dependent
public final class TutorLearningTools {
    private static final int MAX_CONTENT_LENGTH = 12_000;

    private final JourneyApplicationService journeys;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public TutorLearningTools(JourneyApplicationService journeys) {
        this.journeys = Objects.requireNonNull(journeys, "journeys must not be null");
    }

    @Tool(
            name = "save_learning_content",
            description = "校验当前 LearnUnit 的 Concept、Example、Practice，并保存到 Learning Domain 内容快照。")
    public String saveLearningContent(
            TutorContext context,
            @ToolParam(name = "content_json", description = "包含 concept、example、practice 三个文本字段的 JSON")
            String contentJson) {
        var journey = currentJourney(context);
        var unit = currentUnit(journey);
        if (!unit.content().isBlank()) {
            return contentResult(unit);
        }

        var content = parseContent(contentJson);
        var saved = journeys.recordLearnUnitContent(context.journeyId(), unit.code(), content);
        return contentResult(currentUnit(saved));
    }

    private LearningJourney currentJourney(TutorContext context) {
        if (context == null || context.mode() != TutorSessionMode.LEARNING) {
            throw new IllegalStateException("学习内容工具只在 LEARNING Session 中可用");
        }
        var journey = journeys.learningJourneyFor(context.journeyId());
        if (journey.currentItem() == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        return journey;
    }

    private String parseContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank() || contentJson.length() > MAX_CONTENT_LENGTH) {
            throw LearningRequestException.badRequest("INVALID_LEARNING_CONTENT", "学习内容 JSON 不能为空或过长");
        }
        try {
            var root = objectMapper.readTree(contentJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("content must be a JSON object");
            }
            var content = """
                    ## Concept
                    %s

                    ## Example
                    %s

                    ## Practice
                    %s
                    """.formatted(
                    requiredText(root, "concept"),
                    requiredText(root, "example"),
                    requiredText(root, "practice"))
                    .strip();
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException("content is too long");
            }
            return content;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw LearningRequestException.badRequest(
                    "INVALID_LEARNING_CONTENT", "学习内容必须包含有效的 concept、example 和 practice");
        }
    }

    private String contentResult(LearnUnit unit) {
        var result = new LinkedHashMap<String, Object>();
        result.put("learnUnitCode", unit.code());
        result.put("title", unit.title());
        result.put("content", unit.content());
        return json(result);
    }

    private static LearnUnit currentUnit(LearningJourney journey) {
        return journey.learnUnit(Objects.requireNonNull(journey.currentItem()).learnUnitCode());
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()
                || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("content field must be text: " + field);
        }
        return node.get(field).asText().strip();
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化学习内容工具结果", error);
        }
    }
}
