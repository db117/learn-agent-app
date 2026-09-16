package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;

/** 受 Workspace 约束的代码执行边界；具体 Local/Sandbox 实现由后续阶段提供。 */
public interface ExecutionEnvironment {
    /** 在指定 Workspace 中执行受限操作，不接受任意 shell 命令。 */
    ExecutionResult execute(Workspace workspace, ExecutionRequest request);
}
