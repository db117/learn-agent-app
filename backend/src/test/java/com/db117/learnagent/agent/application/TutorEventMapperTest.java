package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.TutorEventType;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TutorEventMapperTest {
    @Test
    void projectsToolStartWithoutLeakingArguments() {
        var event = new Event(
                EventType.REASONING,
                new AssistantMessage(ToolUseBlock.builder()
                        .id("call-1")
                        .name("read_file")
                        .input(java.util.Map.of("path", ".env"))
                        .build()),
                false);

        var projections = TutorEventMapper.map(event);

        assertEquals(List.of(TutorEventType.TOOL_STARTED),
                projections.stream().map(TutorEventMapper.Projection::type).toList());
        assertEquals("已开始工具 read_file", projections.getFirst().text());
        org.junit.jupiter.api.Assertions.assertFalse(projections.getFirst().text().contains(".env"));
    }

    @Test
    void projectsToolFailureAndWorkspaceChange() {
        var failed = new ToolResultBlock(
                "call-1", "compile", List.of(TextBlock.builder().text("Error: bad").build()), null)
                .withState(ToolResultState.ERROR);
        var written = new ToolResultBlock(
                "call-2", "write_file", List.of(TextBlock.builder().text("ok").build()), null);

        var failedProjection = TutorEventMapper.map(
                new Event(EventType.TOOL_RESULT, new ToolResultMessage(failed), true));
        var writeProjection = TutorEventMapper.map(
                new Event(EventType.TOOL_RESULT, new ToolResultMessage(written), true));

        assertEquals(TutorEventType.TOOL_FAILED, failedProjection.getFirst().type());
        assertEquals(List.of(TutorEventType.TOOL_COMPLETED, TutorEventType.WORKSPACE_CHANGED),
                writeProjection.stream().map(TutorEventMapper.Projection::type).toList());
    }

    @Test
    void projectsSkillLoadingAsAStableUiEvent() {
        var loaded = new ToolResultBlock(
                "call-3", "load_skill_through_path",
                List.of(TextBlock.builder().text("internal skill body").build()), null);

        var projection = TutorEventMapper.map(
                new Event(EventType.TOOL_RESULT, new ToolResultMessage(loaded), true));

        assertEquals(List.of(TutorEventType.SKILL_LOADED),
                projection.stream().map(TutorEventMapper.Projection::type).toList());
        assertEquals("已加载 Skill", projection.getFirst().text());
    }
}
