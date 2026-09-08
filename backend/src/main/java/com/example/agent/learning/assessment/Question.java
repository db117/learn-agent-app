package com.example.agent.learning.assessment;

/**
 * 题库中的不可变题目定义，对应 SQLite 的 {@code question} 表。
 *
 * <p>题目只能新增或通过 {@code question_retirement} soft delete，不能更新题干、
 * 正确答案、分值或评分规则；历史 Assessment 因此可以继续读取原题。</p>
 *
 * @param id 题目稳定主键
 * @param learnUnitCode 题目所属 LearnUnit
 * @param type 选择题或 Coding 题
 * @param difficulty 题目难度
 * @param prompt 题干
 * @param points 题目满分
 * @param configJson 选择题选项和正确答案等配置 JSON
 * @param rubricJson Coding 评分标准 JSON
 * @param language Coding 要求的语言
 * @param starterCode Coding 起始代码
 * @param referenceConceptsJson 题目涉及的概念 JSON
 * @param diagnosticEligible 是否可用于初始诊断
 */
public record Question(
        String id,
        String learnUnitCode,
        QuestionType type,
        int difficulty,
        String prompt,
        int points,
        String configJson,
        String rubricJson,
        String language,
        String starterCode,
        String referenceConceptsJson,
        boolean diagnosticEligible) {

    public Question {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Question id is required");
        if (learnUnitCode == null || learnUnitCode.isBlank()) throw new IllegalArgumentException("Question learnUnit is required");
        if (type == null) throw new IllegalArgumentException("Question type is required");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Question prompt is required");
        if (points <= 0) throw new IllegalArgumentException("Question points must be positive");
    }
}
