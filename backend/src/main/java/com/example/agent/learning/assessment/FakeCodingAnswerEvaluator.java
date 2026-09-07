package com.example.agent.learning.assessment;

/** 仅供测试使用的确定性 Coding 评分器，不依赖真实模型或网络。 */
public final class FakeCodingAnswerEvaluator implements CodingAnswerEvaluator {

    @Override
    public CodingEvaluationResult evaluate(CodingQuestion question, String submittedCode) {
        if (submittedCode == null || submittedCode.isBlank()) {
            return new CodingEvaluationResult(0, 0, 0, "Submit an implementation to receive feedback.", java.util.List.of("Code is empty"));
        }
        return new CodingEvaluationResult(60, 20, 20, "Deterministic test evaluation passed.", java.util.List.of());
    }
}
