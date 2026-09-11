package com.example.agent.learning.path;

/** 一个 LearnUnit 的固定教学阶段顺序。 */
public enum LearningPhase {
    EXPLANATION,
    EXAMPLE,
    GUIDED_PRACTICE,
    INDEPENDENT_CHECK;

    public LearningPhase next() {
        return switch (this) {
            case EXPLANATION -> EXAMPLE;
            case EXAMPLE -> GUIDED_PRACTICE;
            case GUIDED_PRACTICE -> INDEPENDENT_CHECK;
            case INDEPENDENT_CHECK -> null;
        };
    }
}
