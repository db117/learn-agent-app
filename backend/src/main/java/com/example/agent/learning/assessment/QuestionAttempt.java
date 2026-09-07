package com.example.agent.learning.assessment;

/**
 * 一道题在某次 Attempt 中的答案、评分和反馈快照。
 *
 * @param questionId 题目编码
 * @param assessmentAttemptId 所属 Attempt
 * @param answerJson 原始答案 JSON
 * @param score 本题得分；Coding 尚未评估时为空
 * @param maxScore 本题满分
 * @param feedback 评分反馈
 * @param correct 选择题是否答对；Coding 使用维度分数，因此可为空
 * @param submittedCode Coding 提交内容
 * @param evaluationJson Coding 评估维度 JSON
 * @param selectedOptionIdsJson 选择题选项编码 JSON
 */
public record QuestionAttempt(
        String questionId,
        String assessmentAttemptId,
        String answerJson,
        Integer score,
        int maxScore,
        String feedback,
        Boolean correct,
        String submittedCode,
        String evaluationJson,
        String selectedOptionIdsJson) {
}
