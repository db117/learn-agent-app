package com.db117.learnagent.shared.domain;

import java.time.Instant;

/** 集中保存跨 Domain 共用的边界校验，避免各聚合重复定义不同规则。 */
public final class DomainChecks {
    private DomainChecks() {
    }

    public static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolation(field + " must not be blank");
        }
        return value.trim();
    }

    public static Instant time(Instant value, String field) {
        if (value == null) {
            throw new DomainRuleViolation(field + " must not be null");
        }
        return value;
    }

    public static long id(long value, String field) {
        if (value <= 0) {
            throw new DomainRuleViolation(field + " must be positive");
        }
        return value;
    }
}
