package com.db117.learnagent.learning.api;

import com.db117.learnagent.workspace.domain.Workspace;
import com.db117.learnagent.workspace.domain.WorkspaceReference;

/**
 * Bootstrap 返回的稳定 Workspace 身份；不包含本地绝对路径。
 *
 * @param kind Workspace 业务类型，例如 {@code LEARNING}
 * @param id Workspace 所属 Journey 或 Project 的主键
 * @param reference 不包含宿主机路径的外部引用，例如 {@code learning:7}
 */
public record WorkspaceDescriptor(
        String kind,
        long id,
        String reference) {
    public static WorkspaceDescriptor from(Workspace workspace) {
        WorkspaceReference reference = workspace.reference();
        return new WorkspaceDescriptor(
                reference.kind().name(),
                reference.ownerId(),
                reference.externalForm());
    }
}
