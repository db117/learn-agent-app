package com.example.agent.learning.scoring;

/**
 * 一次评估的确定性分数摘要，所有分数均为 0 到 100 的百分比。
 *
 * @param choiceScore 选择题类型得分
 * @param codingScore Coding 题类型得分
 * @param totalScore 按评估规则计算出的总分
 * @param hasChoiceQuestions 是否包含选择题
 * @param hasCodingQuestions 是否包含 Coding 题
 */
public record AssessmentScore(
        int choiceScore,
        int codingScore,
        int totalScore,
        boolean hasChoiceQuestions,
        boolean hasCodingQuestions) {
}
