package com.example.agent.learning.assessment;

/**
 * 提供给 CodingAnswerEvaluator 的 Coding 题只读视图。
 *
 * @param id 题目主键
 * @param skillCode 题目所属技能
 * @param prompt 题干
 * @param language 要求使用的编程语言
 * @param starterCode 起始代码
 * @param rubricJson 评分维度和权重 JSON
 * @param referenceConceptsJson 参考概念 JSON
 * @param maxPoints 题目满分
 */
public record CodingQuestion(
        String id,
        String skillCode,
        String prompt,
        String language,
        String starterCode,
        String rubricJson,
        String referenceConceptsJson,
        int maxPoints) {

    public static CodingQuestion from(Question question) {
        if (question.type() != QuestionType.CODING) {
            throw new IllegalArgumentException("Question is not a coding question: " + question.id());
        }
        return new CodingQuestion(
                question.id(),
                question.skillCode(),
                question.prompt(),
                question.language(),
                question.starterCode(),
                question.rubricJson(),
                question.referenceConceptsJson(),
                question.points());
    }
}
