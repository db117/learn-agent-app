package com.example.agent.learning.journey;

/**
 * Journey 创建时保存的学习者背景，用于诊断选题和 Tutor 上下文。
 *
 * @param journeyId 所属 Journey
 * @param primaryLanguage 学习者已有的主要编程语言或背景
 * @param experienceYears 相关经验年数，可为空
 * @param selfDescription 学习者对当前水平的补充说明
 * @param learningGoal 具体学习目标
 */
public record LearnerProfile(
        String journeyId,
        String primaryLanguage,
        Integer experienceYears,
        String selfDescription,
        String learningGoal) {
}
