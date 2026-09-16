package com.db117.learnagent.workspace.api;

public record WorkspaceContentRequest(
        /** 要保存的 UTF-8 文本内容。 */
        String content) {
}
