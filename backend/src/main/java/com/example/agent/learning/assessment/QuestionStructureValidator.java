package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 由 Java 负责的模型生成 Question 定义结构校验。 */
public final class QuestionStructureValidator {

    private QuestionStructureValidator() {
    }

    /** 校验与具体 LearnUnit 无关的字段。 */
    public static void validate(Question question) {
        if (question == null) throw new IllegalArgumentException("Question is required");
        if (question.difficulty() < 1 || question.difficulty() > 5) {
            throw new IllegalArgumentException("Question difficulty must be between 1 and 5");
        }
        if (question.type() == QuestionType.MULTIPLE_CHOICE) {
            validateMultipleChoice(question);
        } else {
            validateCoding(question);
        }
        validateReferenceConcepts(question.referenceConcepts());
    }

    /** 校验题目及其与生成结果中 LearnUnit 的关系。 */
    public static void validate(Question question, LearnUnit learnUnit) {
        validate(question);
        if (learnUnit == null || !learnUnit.code().equals(question.learnUnitCode())) {
            throw new IllegalArgumentException("Question belongs to an unknown LearnUnit");
        }
        if (question.type() == QuestionType.CODING && learnUnit.minCodingScore() == null) {
            throw new IllegalArgumentException("Coding question requires a coding learning objective: " + learnUnit.code());
        }
    }

    private static void validateMultipleChoice(Question question) {
        MultipleChoiceConfig config = question.config();
        if (config == null || config.options().size() < 2 || config.correctOptionIds().isEmpty()) {
            throw new IllegalArgumentException("Multiple-choice config needs options and correctOptionIds");
        }
        Set<String> optionIds = new HashSet<>();
        for (QuestionOption option : config.options()) {
            if (!optionIds.add(option.id())) {
                throw new IllegalArgumentException("Multiple-choice options must have unique ids and text");
            }
        }
        Set<String> correctIds = new HashSet<>();
        for (String value : config.correctOptionIds()) {
            if (value == null || value.isBlank() || !correctIds.add(value) || !optionIds.contains(value)) {
                throw new IllegalArgumentException("correctOptionIds must reference unique options");
            }
        }
        if (!config.multiple() && correctIds.size() != 1) {
            throw new IllegalArgumentException("Single-answer questions need exactly one correct option");
        }
    }

    private static void validateCoding(Question question) {
        if (question.language() == null || question.language().isBlank()) {
            throw new IllegalArgumentException("Coding question language is required");
        }
        if (question.rubric() == null) throw new IllegalArgumentException("Coding rubric is required");
    }

    private static void validateReferenceConcepts(List<String> concepts) {
        for (String concept : concepts) {
            if (concept == null || concept.isBlank()) {
                throw new IllegalArgumentException("referenceConcepts must contain non-empty strings");
            }
        }
    }
}
