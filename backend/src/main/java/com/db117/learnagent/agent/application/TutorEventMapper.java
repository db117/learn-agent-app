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

        ArrayList<TutorEventMapper.Projection> projections = new ArrayList<Projection>();
        for (ToolResultBlock block : event.getMessage().getContentBlocks(ToolResultBlock.class)) {
            String toolName = block.getName() == null || block.getName().isBlank()
                    ? "工具" : block.getName();
            boolean failed = block.getState() != null && "ERROR".equals(block.getState().name());
            TutorEventType type = failed
                    ? TutorEventType.TOOL_FAILED
                    : "load_skill_through_path".equals(toolName)
                    ? TutorEventType.SKILL_LOADED
                    : TutorEventType.TOOL_COMPLETED;
            String errorCode = failed ? "TOOL_FAILED" : null;
            projections.add(new Projection(
                    type,
                    failed
                            ? "工具失败 " + toolName
                            : "load_skill_through_path".equals(toolName)
                            ? "已加载 Skill"
                            : "已完成工具 " + toolName,
                    errorCode));
            if (!failed && changesWorkspace(toolName)) {
                projections.add(new Projection(TutorEventType.WORKSPACE_CHANGED, "Workspace 已更新", null));
            }
        }
        return List.copyOf(projections);
    }

    private static boolean changesWorkspace(String toolName) {
        return switch (toolName) {
            case "write_file", "initialize_npm_project", "install_typescript", "compile_project" -> true;
            default -> false;
        };
    }

    /**
     * AgentScope 事件的安全投影。
     *
     * @param type 面向 UI 的稳定事件类型
     * @param text 不包含工具输入和完整输出的活动文本
     * @param errorCode 失败时的稳定错误码
     */
    record Projection(
            TutorEventType type,
            String text,
            String errorCode) {
    }
}
