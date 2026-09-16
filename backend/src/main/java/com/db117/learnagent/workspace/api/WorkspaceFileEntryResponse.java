package com.db117.learnagent.workspace.api;

import com.db117.learnagent.workspace.domain.WorkspaceFileEntry;

import java.time.Instant;

public record WorkspaceFileEntryResponse(
        /** Workspace 根内的 POSIX 相对路径。 */
        String path,
        /** 文件字节大小。 */
        long size,
        /** 文件最后修改时间。 */
        Instant modifiedAt) {
    public static WorkspaceFileEntryResponse from(WorkspaceFileEntry entry) {
        return new WorkspaceFileEntryResponse(entry.path(), entry.size(), entry.modifiedAt());
    }
}
