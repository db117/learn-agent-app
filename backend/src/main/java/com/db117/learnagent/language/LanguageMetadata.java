package com.db117.learnagent.language;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.Set;

/**
 * LanguagePack 的展示和文件识别元数据。
 *
 * @param displayName 面向用户展示的语言名称
 * @param fileExtensions 该语言支持的文件扩展名，不包含前导点且至少包含一项
 */
public record LanguageMetadata(String displayName, Set<String> fileExtensions) {
    public LanguageMetadata {
        displayName = DomainChecks.text(displayName, "displayName");
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            throw new DomainRuleViolation("fileExtensions must not be empty");
        }
        fileExtensions = fileExtensions.stream()
                .map(extension -> DomainChecks.text(extension, "fileExtension"))
                .map(extension -> extension.startsWith(".")
                        ? throwRule("fileExtension must not start with a dot")
                        : extension)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String throwRule(String message) {
        throw new DomainRuleViolation(message);
    }
}
