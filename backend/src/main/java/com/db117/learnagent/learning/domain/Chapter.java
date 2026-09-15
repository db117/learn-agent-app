package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

/**
 * Journey 内的内容分组；同一 {@code code} 只在当前 Journey 内唯一。
 *
 * @param id SQLite 自增主键；未保存的内容快照为 {@code null}
 * @param code Journey 内稳定的章节编码
 * @param title 面向学习者展示的章节名称
 * @param sequence 章节的稳定排序值，不能为负数
 */
public record Chapter(Long id, String code, String title, int sequence) {
    public Chapter {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        code = DomainChecks.text(code, "code");
        title = DomainChecks.text(title, "title");
        if (sequence < 0) {
            throw new DomainRuleViolation("sequence must not be negative");
        }
    }

    public static Chapter create(String code, String title, int sequence) {
        return new Chapter(null, code, title, sequence);
    }

    public Chapter withId(long persistedId) {
        return new Chapter(DomainChecks.id(persistedId, "id"), code, title, sequence);
    }
}
