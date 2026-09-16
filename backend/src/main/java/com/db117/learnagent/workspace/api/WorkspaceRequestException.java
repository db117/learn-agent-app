package com.db117.learnagent.workspace.api;

public final class WorkspaceRequestException extends RuntimeException {
    private final int status;
    private final String code;

    private WorkspaceRequestException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public static WorkspaceRequestException badRequest(IllegalArgumentException error) {
        return new WorkspaceRequestException(400, "INVALID_WORKSPACE_REQUEST",
                "Workspace 路径或内容无效", error);
    }

    public static WorkspaceRequestException notFound(String message, Throwable cause) {
        return new WorkspaceRequestException(404, "WORKSPACE_FILE_NOT_FOUND", message, cause);
    }

    public static WorkspaceRequestException internal(Throwable cause) {
        return new WorkspaceRequestException(500, "WORKSPACE_IO_ERROR",
                "Workspace 文件操作失败", cause);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
