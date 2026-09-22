package com.db117.learnagent.language;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 初始化 Workspace 的单个文件模板。
 *
 * @param path Workspace 内的相对文件路径，不允许绝对路径或越出 Workspace 的 {@code ..}
 * @param content 写入该相对路径的初始文件内容
 */
public record WorkspaceTemplate(String path, String content) {
    public WorkspaceTemplate {
        path = DomainChecks.text(path, "path");
        try {
            Path normalized = Path.of(path).normalize();
            if (Path.of(path).isAbsolute()
                    || normalized.getNameCount() == 0
                    || normalized.startsWith("..")) {
                throw new DomainRuleViolation("path must be a relative Workspace path: " + path);
            }
        } catch (InvalidPathException error) {
            throw new DomainRuleViolation("path must be a valid Workspace path: " + path);
        }
        if (content == null) {
            throw new DomainRuleViolation("content must not be null");
        }
    }
}
