package com.example.agent.learning.assessment;

/** 选择题公开或内部使用的一个选项。 */
public record QuestionOption(String id, String text) {

    public QuestionOption {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Question option id is required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Question option text is required");
        id = id.trim();
        text = text.trim();
    }
}
