package com.example.agent.learning.generation;

/** Supported model-assisted operations and their safe user-facing terminal messages. */
public enum GenerationOperation {
    JOURNEY_OUTLINE(
            "JOURNEY_OUTLINE",
            "本次 Journey 创建已取消。",
            "大纲生成失败，请检查目标或模型配置后重试。",
            "journey draft is being saved"),
    LEARN_UNIT_CONTENT(
            "LEARN_UNIT_CONTENT",
            "本次 LearnUnit 内容生成已取消。",
            "LearnUnit 内容生成失败，请重试。",
            "LearnUnit content is being saved"),
    DIAGNOSTIC_QUESTIONS(
            "DIAGNOSTIC_QUESTIONS",
            "本次诊断题生成已取消。",
            "诊断题生成失败，请检查模型配置后重试。",
            "Diagnostic questions are being saved"),
    CODING_EVALUATION(
            "CODING_EVALUATION",
            "本次 Coding 评估已取消，答案草稿已保留。",
            "Coding 评估失败，答案草稿已保留，请重试。",
            "Coding evaluation is being saved");

    private final String wireValue;
    private final String cancellationMessage;
    private final String failureMessage;
    private final String persistenceMessage;

    GenerationOperation(
            String wireValue, String cancellationMessage, String failureMessage, String persistenceMessage) {
        this.wireValue = wireValue;
        this.cancellationMessage = cancellationMessage;
        this.failureMessage = failureMessage;
        this.persistenceMessage = persistenceMessage;
    }

    public String wireValue() {
        return wireValue;
    }

    String cancellationMessage() {
        return cancellationMessage;
    }

    String failureMessage() {
        return failureMessage;
    }

    String persistenceMessage() {
        return persistenceMessage;
    }
}
