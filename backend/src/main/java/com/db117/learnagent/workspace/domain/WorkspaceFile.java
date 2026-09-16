package com.db117.learnagent.workspace.domain;

import java.time.Instant;

/** readFile/writeFile 返回的 UTF-8 文本文件结果。 */
public record WorkspaceFile(
        /** Workspace 根内的 POSIX 相对路径。 */
        String path,
        /** UTF-8 文本内容。 */
        String content,
        /** 文件字节大小。 */
        long size,
        /** 文件最后修改时间。 */
        Instant modifiedAt) {
}
