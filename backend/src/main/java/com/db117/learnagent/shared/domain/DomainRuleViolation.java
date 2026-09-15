package com.db117.learnagent.shared.domain;

/** 领域规则不满足时使用的快速失败异常。 */
public final class DomainRuleViolation extends IllegalArgumentException {
    public DomainRuleViolation(String message) {
        super(message);
    }
}
