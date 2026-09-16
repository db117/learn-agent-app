package com.db117.learnagent.workspace.domain;

import java.nio.file.Path;

/** Project 使用的代码 Workspace。 */
public final class ProjectWorkspace extends Workspace {
    public ProjectWorkspace(long projectId, Path root) {
        super(new WorkspaceReference(WorkspaceKind.PROJECT, projectId), root);
    }
}
