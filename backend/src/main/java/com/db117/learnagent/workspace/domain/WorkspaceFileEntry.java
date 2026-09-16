package com.db117.learnagent.workspace.domain;

import java.time.Instant;

/** listFiles 返回的单个普通文件条目。 */
public record WorkspaceFileEntry(
        /** Workspace 根内的 POSIX 相对路径。 */
        String path,
        /** 文件字节大小。 */
        long size,
        /** 文件最后修改时间。 */
        Instant modifiedAt) {
}
