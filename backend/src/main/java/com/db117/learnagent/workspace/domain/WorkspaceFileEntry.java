package com.db117.learnagent.workspace.domain;

import java.time.Instant;

/**
 * listFiles 返回的单个普通文件条目。
 *
 * @param path Workspace 根内的 POSIX 相对路径
 * @param size 文件字节大小
 * @param modifiedAt 文件最后修改时间
 */
public record WorkspaceFileEntry(
        String path,
        long size,
        Instant modifiedAt) {
}
