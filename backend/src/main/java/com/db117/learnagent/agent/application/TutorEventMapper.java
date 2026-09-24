package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.TutorEventType;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import java.util.ArrayList;
import java.util.List;

/** 把 AgentScope 细粒度事件投影成安全的 TutorEvent；不把工具参数、日志或私有推理传给 UI。 */
final class TutorEventMapper {
    private TutorEventMapper() {
    }

    static List<Projection> map(AgentEvent event) {
        if (event instanceof ToolCallStartEvent toolCall) {
            String toolName = toolCall.getToolCallName();
            if (toolName == null || toolName.isBlank()) {
                return List.of();
            }
            return List.of(new Projection(
                    TutorEventType.TOOL_STARTED,
                    "已开始工具 " + toolName,
                    null));
        }
        if (!(event instanceof ToolResultEndEvent toolResult)) {
            return List.of();
        }
        String toolName = toolResult.getToolCallName() == null || toolResult.getToolCallName().isBlank()
                ? "工具" : toolResult.getToolCallName();
        boolean failed = toolResult.getState() == ToolResultState.ERROR;
        TutorEventType type = failed
                ? TutorEventType.TOOL_FAILED
                : "load_skill_through_path".equals(toolName)
                ? TutorEventType.SKILL_LOADED
                : TutorEventType.TOOL_COMPLETED;
        String errorCode = failed ? "TOOL_FAILED" : null;
        ArrayList<Projection> projections = new ArrayList<Projection>();
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
