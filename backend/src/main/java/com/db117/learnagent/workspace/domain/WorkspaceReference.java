package com.db117.learnagent.workspace.domain;

import java.util.Locale;

/**
 * Workspace 的稳定身份。
 *
 * @param kind Workspace 的业务归属类型
 * @param ownerId 拥有该 Workspace 的 Journey 或 Project 主键
 */
public record WorkspaceReference(
        WorkspaceKind kind,
        long ownerId) {
    public WorkspaceReference {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be positive");
        }
    }

    /** 返回不包含宿主机路径的稳定外部引用。 */
    public String externalForm() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + ownerId;
    }
}
