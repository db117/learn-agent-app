package com.db117.learnagent.workspace.api;

import com.db117.learnagent.workspace.domain.WorkspaceFileEntry;

import java.time.Instant;

/**
 * Workspace 文件列表中的稳定文件条目。
 *
 * @param path Workspace 根内的 POSIX 相对路径
 * @param size 文件字节大小
 * @param modifiedAt 文件最后修改时间
 */
public record WorkspaceFileEntryResponse(
        String path,
        long size,
        Instant modifiedAt) {
    public static WorkspaceFileEntryResponse from(WorkspaceFileEntry entry) {
        return new WorkspaceFileEntryResponse(entry.path(), entry.size(), entry.modifiedAt());
    }
}
