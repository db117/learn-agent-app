package com.example.agent.agent;

import com.example.agent.persistence.TutorEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 将 AgentScope 事件投影成框架无关的 TutorEvent。 */
public class TutorEventProjector {

    private static final String SKILL_LOAD_TOOL = "load_skill_through_path";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConcurrentHashMap<String, StringBuilder> responseText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> skillNames = new ConcurrentHashMap<>();

    /** 转换一个 AgentScope 事件；内部事件不会泄漏到 HTTP、SSE 或 SQLite。 */
    public Optional<TutorEvent> project(String sessionId, String runId, AgentEvent event) {
        Instant timestamp = Instant.now();
        if (event instanceof ThinkingBlockStartEvent) {
            return Optional.of(TutorEvent.reasoningSummary(sessionId, runId, timestamp));
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            String text = textDelta.getDelta() == null ? "" : textDelta.getDelta();
            if (!text.isEmpty()) responseText.computeIfAbsent(runId, ignored -> new StringBuilder()).append(text);
            return Optional.of(TutorEvent.textDelta(sessionId, runId, "tutor_agent", text, timestamp));
        }
        if (event instanceof ToolCallStartEvent toolCall) {
            if (isSkillLoad(toolCall.getToolCallName())) {
                return Optional.of(TutorEvent.skillLoadStart(sessionId, runId, timestamp));
            }
            return Optional.of(TutorEvent.toolCall(
                    sessionId, runId, "tutor_agent", toolCall.getToolCallName(), timestamp));
        }
        if (event instanceof ToolCallDeltaEvent toolCall && isSkillLoad(toolCall.getToolCallName())) {
            String skillName = safeSkillName(toolCall.getDelta());
            if (skillName != null) skillNames.put(skillKey(runId, toolCall.getToolCallId()), skillName);
            return Optional.empty();
        }
        if (event instanceof ToolResultEndEvent toolResult && isSkillLoad(toolResult.getToolCallName())) {
            String skillName = skillNames.remove(skillKey(runId, toolResult.getToolCallId()));
            if (toolResult.getState() != ToolResultState.SUCCESS) {
                throw new IllegalStateException("AgentScope Skill load failed");
            }
            return Optional.of(TutorEvent.skillLoadComplete(sessionId, runId, skillName, timestamp));
        }
        if (event instanceof ToolResultTextDeltaEvent toolResult) {
            if (isSkillLoad(toolResult.getToolCallName())) return Optional.empty();
            return Optional.of(TutorEvent.toolResult(
                    sessionId, runId, "tool", toolResult.getToolCallName(), timestamp));
        }
        return Optional.empty();
    }

    /** 取出一次调用的完整回答，并释放投影器中的临时状态。 */
    public String takeResponse(String runId) {
        StringBuilder accumulated = responseText.remove(runId);
        return accumulated == null ? "" : accumulated.toString();
    }

    /** 清理失败、取消或完成调用残留的投影状态。 */
    public void clear(String runId) {
        responseText.remove(runId);
        skillNames.keySet().removeIf(key -> key.startsWith(runId + ":"));
    }

    private static boolean isSkillLoad(String toolName) {
        return SKILL_LOAD_TOOL.equals(toolName);
    }

    private static String skillKey(String runId, String toolCallId) {
        return runId + ":" + toolCallId;
    }

    private static String safeSkillName(String delta) {
        if (delta == null || delta.isBlank()) return null;
        try {
            JsonNode payload = JSON.readTree(delta);
            JsonNode value = payload.get("skillId");
            if (value == null) value = payload.get("skill_id");
            if (value != null && value.isTextual() && value.textValue().matches("[A-Za-z0-9._-]{1,120}")) {
                return value.textValue();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
