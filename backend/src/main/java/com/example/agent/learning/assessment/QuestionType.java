package com.example.agent.learning.assessment;

/** Phase 2 支持的题型。 */
public enum QuestionType {
    /** 全部选项集合正确才得分。 */
    MULTIPLE_CHOICE,
    /** 由 CodingAnswerEvaluator 返回维度分。 */
    CODING
}
