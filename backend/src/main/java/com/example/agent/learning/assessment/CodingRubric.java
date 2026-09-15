package com.example.agent.learning.assessment;

import java.util.Objects;

/** Coding 题固定的三维评分标准。 */
public record CodingRubric(
        Integer correctness,
        Integer languageUsage,
        Integer clarity) {

    public CodingRubric {
        correctness = requiredWeight(correctness, "correctness");
        languageUsage = requiredWeight(languageUsage, "languageUsage");
        clarity = requiredWeight(clarity, "clarity");
    }

    private static Integer requiredWeight(Integer value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be 0..100");
        return value;
    }
}
