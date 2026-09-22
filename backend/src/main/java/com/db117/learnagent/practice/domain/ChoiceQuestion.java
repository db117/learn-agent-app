package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 选择题的领域事实；正确选项只在后端领域对象中存在。
 *
 * @param prompt 题干
 * @param options 对学习者公开的选项列表
 * @param correctOptionId 后端判定使用的正确选项标识
 */
public record ChoiceQuestion(
        String prompt,
        List<ChoiceOption> options,
        String correctOptionId) {

    public ChoiceQuestion {
        prompt = DomainChecks.text(prompt, "prompt");
        if (options == null || options.size() < 2) {
            throw new DomainRuleViolation("choice question must have at least two options");
        }
        ArrayList<ChoiceOption> normalizedOptions = new ArrayList<ChoiceOption>();
        HashSet<String> optionIds = new HashSet<String>();
        for (ChoiceOption option : options) {
            if (option == null || !optionIds.add(option.id())) {
                throw new DomainRuleViolation("choice question option ids must be unique");
            }
            normalizedOptions.add(option);
        }
        options = List.copyOf(normalizedOptions);
        correctOptionId = DomainChecks.text(correctOptionId, "correctOptionId");
        if (!optionIds.contains(correctOptionId)) {
            throw new DomainRuleViolation("correctOptionId must refer to an option");
        }
    }

    public boolean hasOption(String optionId) {
        return optionId != null && options.stream().anyMatch(option -> option.id().equals(optionId));
    }

    public boolean isCorrect(String optionId) {
        return Objects.equals(correctOptionId, optionId);
    }
}
