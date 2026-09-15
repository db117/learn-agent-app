package com.db117.learnagent.agent.domain;

import java.util.Objects;

/** Agent Runtime 当前绑定的工作区身份；不把宿主机路径暴露给 Tutor 或客户端。 */
public record WorkspaceBinding(
        /** 工作区类型；Step 3 只允许 {@code AGENT}。 */
        String kind,
        /** 工作区在 Runtime 内的稳定标识。 */
        String id) {

    public WorkspaceBinding {
        kind = requireText(kind, "kind");
        id = requireText(id, "id");
    }

    public static WorkspaceBinding agent() {
        return new WorkspaceBinding("AGENT", "agent");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        var normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
