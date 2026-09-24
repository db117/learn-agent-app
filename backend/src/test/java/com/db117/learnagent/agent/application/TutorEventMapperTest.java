package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.TutorEventType;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TutorEventMapperTest {
    @Test
    void projectsToolStartWithoutLeakingArguments() {
        ToolCallStartEvent event = new ToolCallStartEvent("reply-1", "call-1", "read_file");

        List<TutorEventMapper.Projection> projections = TutorEventMapper.map(event);

        assertEquals(List.of(TutorEventType.TOOL_STARTED),
                projections.stream().map(TutorEventMapper.Projection::type).toList());
        assertEquals("已开始工具 read_file", projections.getFirst().text());
        org.junit.jupiter.api.Assertions.assertFalse(projections.getFirst().text().contains(".env"));
    }

    @Test
    void projectsToolFailureAndWorkspaceChange() {
        List<TutorEventMapper.Projection> failedProjection = TutorEventMapper.map(
                new ToolResultEndEvent("reply-1", "call-1", "compile", ToolResultState.ERROR));
        List<TutorEventMapper.Projection> writeProjection = TutorEventMapper.map(
                new ToolResultEndEvent("reply-1", "call-2", "write_file", ToolResultState.SUCCESS));

        assertEquals(TutorEventType.TOOL_FAILED, failedProjection.getFirst().type());
        assertEquals(List.of(TutorEventType.TOOL_COMPLETED, TutorEventType.WORKSPACE_CHANGED),
                writeProjection.stream().map(TutorEventMapper.Projection::type).toList());
        assertEquals(List.of(TutorEventType.TOOL_COMPLETED, TutorEventType.WORKSPACE_CHANGED),
                TutorEventMapper.map(new ToolResultEndEvent(
                                "reply-1", "call-3", "compile_project", ToolResultState.SUCCESS))
                        .stream().map(TutorEventMapper.Projection::type).toList());
    }

    @Test
    void projectsSkillLoadingAsAStableUiEvent() {
        List<TutorEventMapper.Projection> projection = TutorEventMapper.map(
                new ToolResultEndEvent(
                        "reply-1", "call-3", "load_skill_through_path", ToolResultState.SUCCESS));

        assertEquals(List.of(TutorEventType.SKILL_LOADED),
                projection.stream().map(TutorEventMapper.Projection::type).toList());
        assertEquals("已加载 Skill", projection.getFirst().text());
    }
}
