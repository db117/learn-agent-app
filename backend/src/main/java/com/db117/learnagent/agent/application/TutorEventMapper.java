package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.TutorEventType;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/** 把 AgentScope 粗粒度事件投影成安全的 TutorEvent；不把工具参数、日志或私有推理传给 UI。 */
final class TutorEventMapper {
    private TutorEventMapper() {
    }

    static List<Projection> map(Event event) {
        if (event == null || event.getMessage() == null) {
            return List.of();
        }
        if (event.getType() == EventType.REASONING) {
            return event.getMessage().getContentBlocks(ToolUseBlock.class).stream()
                    .filter(block -> block.getName() != null && !block.getName().isBlank())
                    .map(block -> new Projection(
                            TutorEventType.TOOL_STARTED,
                            "已开始工具 " + block.getName(),
                            null))
                    .toList();
        }
        if (event.getType() != EventType.TOOL_RESULT) {
            return List.of();
        }

        var projections = new ArrayList<Projection>();
        for (var block : event.getMessage().getContentBlocks(ToolResultBlock.class)) {
            var toolName = block.getName() == null || block.getName().isBlank()
                    ? "工具" : block.getName();
            var failed = block.getState() != null && "ERROR".equals(block.getState().name());
            var type = failed ? TutorEventType.TOOL_FAILED : TutorEventType.TOOL_COMPLETED;
            var errorCode = failed ? "TOOL_FAILED" : null;
            projections.add(new Projection(
                    type,
                    (failed ? "工具失败 " : "已完成工具 ") + toolName,
                    errorCode));
            if (!failed && "write_file".equals(toolName)) {
                projections.add(new Projection(TutorEventType.WORKSPACE_CHANGED, "Workspace 已更新", null));
            }
        }
        return List.copyOf(projections);
    }

    record Projection(
            /** 面向 UI 的稳定事件类型。 */
            TutorEventType type,
            /** 不包含工具输入和完整输出的活动文本。 */
            String text,
            /** 失败时的稳定错误码。 */
            String errorCode) {
    }
}
