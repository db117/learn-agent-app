package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainChecks;

/**
 * 选择题对用户公开的一个选项；不包含正确答案标记。
 *
 * @param id 选项在题目内的稳定标识
 * @param label 展示给学习者的选项文本
 */
public record ChoiceOption(
        String id,
        String label) {

    public ChoiceOption {
        id = DomainChecks.text(id, "id");
        label = DomainChecks.text(label, "label");
    }
}
