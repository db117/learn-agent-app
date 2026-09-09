package com.example.agent.learning.assessment;

import java.util.List;

/**
 * Coding 评估器返回的维度分数。
 *
 * <p>模型只负责给出三个受限维度和反馈，是否通过由 Java 的 Score/Pass
 * 规则计算，避免把业务决策交给 LLM。</p>
 *
 * @param correctness 正确性分数，范围 0..60
 * @param languageUsage 语言使用分数，范围 0..20
 * @param clarity 清晰度分数，范围 0..20
 * @param feedback 面向学习者的反馈
 * @param issues 问题列表
 */
public record CodingEvaluationResult(
        int correctness,
        int languageUsage,
        int clarity,
        String feedback,
        List<String> issues) {

    public CodingEvaluationResult {
        if (correctness < 0 || correctness > 60) throw new IllegalArgumentException("correctness must be 0..60");
        if (languageUsage < 0 || languageUsage > 20) throw new IllegalArgumentException("languageUsage must be 0..20");
        if (clarity < 0 || clarity > 20) throw new IllegalArgumentException("clarity must be 0..20");
        if (feedback == null || feedback.isBlank()) throw new IllegalArgumentException("feedback is required");
        issues = List.copyOf(issues == null ? List.of() : issues);
        if (issues.stream().anyMatch(issue -> issue == null || issue.isBlank())) {
            throw new IllegalArgumentException("issues must contain non-blank strings");
        }
    }

    public int totalScore() {
        return correctness + languageUsage + clarity;
    }
}
