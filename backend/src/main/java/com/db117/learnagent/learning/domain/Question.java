package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Assessment 的单个问题；CODE 问题的答案由 Workspace 引用表达。
 *
 * @param id SQLite 自增主键；创建时为 {@code null}
 * @param code Assessment 内唯一的问题编码
 * @param type 选择题或编码题类型
 * @param prompt 面向学习者展示的问题文本
 * @param optionIds 选择题的选项编码；CODE 问题必须为空
 * @param correctOptionIds 选择题的标准选项集合；CODE 问题必须为空
 */
public record Question(
        Long id,
        String code,
        QuestionType type,
        String prompt,
        List<String> optionIds,
        Set<String> correctOptionIds) {

    public Question {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        code = DomainChecks.text(code, "code");
        type = type == null ? throwRule("type must not be null") : type;
        prompt = DomainChecks.text(prompt, "prompt");
        optionIds = optionIds == null ? List.of() : optionIds.stream()
                .map(option -> DomainChecks.text(option, "optionId"))
                .toList();
        if (new LinkedHashSet<>(optionIds).size() != optionIds.size()) {
            throw new DomainRuleViolation("optionIds must be unique");
        }
        correctOptionIds = correctOptionIds == null ? Set.of() : correctOptionIds.stream()
                .map(option -> DomainChecks.text(option, "correctOptionId"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (type == QuestionType.CODE) {
            if (!optionIds.isEmpty() || !correctOptionIds.isEmpty()) {
                throw new DomainRuleViolation("CODE question cannot have choice options");
            }
        } else {
            if (optionIds.isEmpty() || correctOptionIds.isEmpty() || !optionIds.containsAll(correctOptionIds)) {
                throw new DomainRuleViolation("choice question options and correct options are invalid");
            }
            if (type == QuestionType.SINGLE_CHOICE && correctOptionIds.size() != 1) {
                throw new DomainRuleViolation("SINGLE_CHOICE needs exactly one correct option");
            }
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public static Question singleChoice(
            String code, String prompt, List<String> optionIds, String correctOptionId) {
        return new Question(null, code, QuestionType.SINGLE_CHOICE, prompt, optionIds, Set.of(correctOptionId));
    }

    public static Question multipleChoice(
            String code, String prompt, List<String> optionIds, Set<String> correctOptionIds) {
        return new Question(null, code, QuestionType.MULTIPLE_CHOICE, prompt, optionIds, correctOptionIds);
    }

    public static Question code(String code, String prompt) {
        return new Question(null, code, QuestionType.CODE, prompt, List.of(), Set.of());
    }

    public Question withId(long persistedId) {
        return new Question(
                DomainChecks.id(persistedId, "id"), code, type, prompt, optionIds, correctOptionIds);
    }
}
