package com.db117.learnagent.workspace.api;

/**
 * 保存 Workspace 文件内容的请求。
 *
 * @param content 要保存的 UTF-8 文本内容
 */
public record WorkspaceContentRequest(
        String content) {
}
