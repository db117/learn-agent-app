package com.db117.learnagent.workspace.domain;

import java.time.Instant;

/**
 * readFile/writeFile 返回的 UTF-8 文本文件结果。
 *
 * @param path Workspace 根内的 POSIX 相对路径
 * @param content UTF-8 文本内容
 * @param size 文件字节大小
 * @param modifiedAt 文件最后修改时间
 */
public record WorkspaceFile(
        String path,
        String content,
        long size,
        Instant modifiedAt) {
}
