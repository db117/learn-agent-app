package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.Set;

/**
 * 一次 Attempt 对问题的提交；源码不进入 Domain 或 SQLite。
 *
 * @param questionCode 被回答的问题编码
 * @param selectedOptionIds 选择题提交的选项编码集合；编码题为空
 * @param workspaceReference 编码题的 Workspace/Artifact 快照引用；选择题为空
 */
public record Answer(String questionCode, Set<String> selectedOptionIds, String workspaceReference) {
    public Answer {
        questionCode = DomainChecks.text(questionCode, "questionCode");
        selectedOptionIds = selectedOptionIds == null ? Set.of() : Set.copyOf(selectedOptionIds);
        if (workspaceReference != null && workspaceReference.isBlank()) {
            workspaceReference = null;
        }
        if (!selectedOptionIds.isEmpty() && workspaceReference != null) {
            throw new DomainRuleViolation("Answer cannot contain choice and code data together");
        }
    }

    public static Answer choice(String questionCode, Set<String> selectedOptionIds) {
        return new Answer(questionCode, selectedOptionIds, null);
    }

    public static Answer code(String questionCode, String workspaceReference) {
        return new Answer(questionCode, Set.of(), DomainChecks.text(workspaceReference, "workspaceReference"));
    }
}
