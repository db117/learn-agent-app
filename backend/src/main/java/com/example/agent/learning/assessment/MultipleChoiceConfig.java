package com.example.agent.learning.assessment;

import java.util.List;
import java.util.Objects;

/** 选择题的固定配置；正确答案只在服务端题目对象中保留。 */
public record MultipleChoiceConfig(
        List<QuestionOption> options,
        List<String> correctOptionIds,
        Boolean multiple) {

    public MultipleChoiceConfig {
        options = List.copyOf(options == null ? List.of() : options);
        correctOptionIds = List.copyOf(correctOptionIds == null ? List.of() : correctOptionIds);
        multiple = Objects.requireNonNull(multiple, "Question multiple flag is required");
    }
}
