package com.example.agent.learning.assessment;

import java.util.List;

public final class QuestionFixtures {

    private QuestionFixtures() {
    }

    public static MultipleChoiceConfig choiceConfig() {
        return choiceConfig(List.of("A"), false);
    }

    public static MultipleChoiceConfig singleChoiceConfig() {
        return new MultipleChoiceConfig(List.of(new QuestionOption("A", "yes")), List.of("A"), false);
    }

    public static MultipleChoiceConfig choiceConfig(List<String> correctOptionIds, boolean multiple) {
        return new MultipleChoiceConfig(
                List.of(new QuestionOption("A", "yes"), new QuestionOption("B", "no")),
                correctOptionIds,
                multiple);
    }

    public static CodingRubric codingRubric() {
        return new CodingRubric(60, 20, 20);
    }
}
