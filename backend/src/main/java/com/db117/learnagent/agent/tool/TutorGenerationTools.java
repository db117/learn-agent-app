package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearnUnitContentService;
import com.db117.learnagent.learning.application.LearningOutlineGenerator;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

/** 把生成类 Skill 接到现有 typed Generator/Application Service；不直接修改 Domain。 */
@Dependent
public final class TutorGenerationTools {
    private final JourneyApplicationService journeys;
    private final LearnUnitContentService contentService;
    private final LearningOutlineGenerator outlineGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public TutorGenerationTools(
            JourneyApplicationService journeys,
            LearnUnitContentService contentService,
            LearningOutlineGenerator outlineGenerator) {
        this.journeys = Objects.requireNonNull(journeys, "journeys must not be null");
        this.contentService = Objects.requireNonNull(contentService, "contentService must not be null");
        this.outlineGenerator = Objects.requireNonNull(outlineGenerator, "outlineGenerator must not be null");
    }

    @Tool(
            name = "generate_learning_outline",
            description = "在规划模式生成可调整的 Learning Journey JSON 大纲草稿；不会保存路径。",
            readOnly = true)
    public String generateLearningOutline(TutorContext context) {
        requireMode(context, TutorSessionMode.PLANNING);
        return outlineGenerator.generate(context);
    }

    @Tool(
            name = "generate_learning_content",
            description = "为当前 LearnUnit 生成 Concept、Example、Practice，并返回已校验的内容快照。")
    public String generateLearningContent(TutorContext context) {
        var unit = currentUnit(context);
        var journey = contentService.ensureCurrentContent(journeys.learningJourneyFor(context.journeyId()));
        unit = journey.learnUnit(unit.code());
        return json(Map.of(
                "learnUnitCode", unit.code(),
                "title", unit.title(),
                "content", unit.content()));
    }

    private LearnUnit currentUnit(TutorContext context) {
        requireMode(context, TutorSessionMode.LEARNING);
        var journey = journeys.learningJourneyFor(context.journeyId());
        var current = journey.currentItem();
        if (current == null) {
            throw new IllegalStateException("当前 LearningJourney 没有可用 LearnUnit");
        }
        return journey.learnUnit(current.learnUnitCode());
    }

    private static void requireMode(TutorContext context, TutorSessionMode expected) {
        if (context == null || context.mode() != expected) {
            throw new IllegalStateException("当前 Session 不支持该生成工具");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化生成结果", error);
        }
    }
}
