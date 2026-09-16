package com.db117.learnagent.workspace.domain;

import java.nio.file.Path;

/** Journey 的 LearningWorkspace；目录身份使用 Journey 主键。 */
public final class LearningWorkspace extends Workspace {
    public LearningWorkspace(long journeyId, Path root) {
        super(new WorkspaceReference(WorkspaceKind.LEARNING, journeyId), root);
    }
}
