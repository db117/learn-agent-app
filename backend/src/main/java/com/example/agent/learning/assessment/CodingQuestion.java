package com.example.agent.learning.assessment;

import java.util.List;

/**
 * 提供给 CodingAnswerEvaluator 的 Coding 题只读视图。
 *
 * @param id 题目主键
 * @param learnUnitCode 题目所属 LearnUnit
 * @param prompt 题干
 * @param language 要求使用的编程语言
 * @param starterCode 起始代码
 * @param rubric 评分维度和权重
 * @param referenceConcepts 参考概念
 * @param maxPoints 题目满分
 */
public record CodingQuestion(
        String id,
        String learnUnitCode,
        String prompt,
        String language,
        String starterCode,
        CodingRubric rubric,
        List<String> referenceConcepts,
        int maxPoints) {

    public static CodingQuestion from(Question question) {
        if (question.type() != QuestionType.CODING) {
            throw new IllegalArgumentException("Question is not a coding question: " + question.id());
        }
        return new CodingQuestion(
                question.id(),
                question.learnUnitCode(),
                question.prompt(),
                question.language(),
                question.starterCode(),
                question.rubric(),
                question.referenceConcepts(),
                question.points());
    }
}
