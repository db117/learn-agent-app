package com.example.agent.learning.assessment;

/**
 * Coding 答案评分边界。
 *
 * <p>实现可以调用 LLM，但必须只返回受限维度分数和反馈；总分及通过状态由 Learning Engine 负责。</p>
 */
public interface CodingAnswerEvaluator {

    /**
     * 评估一份 Coding 答案。
     *
     * @param question 待评估的 Coding 题
     * @param submittedCode 学习者提交的代码
     * @return 已通过范围校验的维度分数和反馈
     */
    CodingEvaluationResult evaluate(CodingQuestion question, String submittedCode);
}
