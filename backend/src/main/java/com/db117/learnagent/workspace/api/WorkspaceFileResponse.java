package com.db117.learnagent.workspace.api;

import com.db117.learnagent.workspace.domain.WorkspaceFile;

import java.time.Instant;

public record WorkspaceFileResponse(
        /** Workspace 根内的 POSIX 相对路径。 */
        String path,
        /** UTF-8 文本内容。 */
        String content,
        /** 文件字节大小。 */
        long size,
        /** 文件最后修改时间。 */
        Instant modifiedAt) {
    public static WorkspaceFileResponse from(WorkspaceFile file) {
        return new WorkspaceFileResponse(file.path(), file.content(), file.size(), file.modifiedAt());
    }
}
