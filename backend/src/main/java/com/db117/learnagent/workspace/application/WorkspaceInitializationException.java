package com.db117.learnagent.workspace.application;

/** Workspace 初始化失败时隐藏本地文件系统异常，避免把宿主机细节暴露给 API。 */
public final class WorkspaceInitializationException extends RuntimeException {
    public WorkspaceInitializationException(Throwable cause) {
        super(cause);
    }
}
