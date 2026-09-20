package com.db117.learnagent.workspace.api;

import com.db117.learnagent.workspace.domain.WorkspaceFile;

import java.time.Instant;

/**
 * Workspace 文件读取结果。
 *
 * @param path Workspace 根内的 POSIX 相对路径
 * @param content UTF-8 文本内容
 * @param size 文件字节大小
 * @param modifiedAt 文件最后修改时间
 */
public record WorkspaceFileResponse(
        String path,
        String content,
        long size,
        Instant modifiedAt) {
    public static WorkspaceFileResponse from(WorkspaceFile file) {
        return new WorkspaceFileResponse(file.path(), file.content(), file.size(), file.modifiedAt());
    }
}
