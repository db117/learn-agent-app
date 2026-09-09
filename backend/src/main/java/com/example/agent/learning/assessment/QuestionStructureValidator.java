package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/** Java-owned structural checks for model-produced Question definitions. */
public final class QuestionStructureValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QuestionStructureValidator() {
    }

    /** Validates fields that are independent of a particular LearnUnit. */
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
        validateReferenceConcepts(question.referenceConceptsJson());
    }

    /** Validates a question and its relationship to the generated LearnUnit. */
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
        JsonNode config = object(question.configJson(), "Multiple-choice config");
        JsonNode options = config.get("options");
        JsonNode correct = config.get("correctOptionIds");
        if (options == null || !options.isArray() || options.size() < 2
                || correct == null || !correct.isArray() || correct.isEmpty()) {
            throw new IllegalArgumentException("Multiple-choice config needs options and correctOptionIds");
        }
        Set<String> optionIds = new HashSet<>();
        for (JsonNode option : options) {
            String id = text(option, "id");
            String label = text(option, "text");
            if (id.isBlank() || !optionIds.add(id) || label.isBlank()) {
                throw new IllegalArgumentException("Multiple-choice options must have unique ids and text");
            }
        }
        Set<String> correctIds = new HashSet<>();
        for (JsonNode value : correct) {
            if (!value.isTextual() || value.textValue().isBlank() || !correctIds.add(value.textValue())
                    || !optionIds.contains(value.textValue())) {
                throw new IllegalArgumentException("correctOptionIds must reference unique options");
            }
        }
        JsonNode multiple = config.get("multiple");
        if (multiple == null || !multiple.isBoolean()) {
            throw new IllegalArgumentException("Multiple-choice multiple must be boolean");
        }
        if (!multiple.asBoolean() && correctIds.size() != 1) {
            throw new IllegalArgumentException("Single-answer questions need exactly one correct option");
        }
    }

    private static void validateCoding(Question question) {
        if (question.language() == null || question.language().isBlank()) {
            throw new IllegalArgumentException("Coding question language is required");
        }
        JsonNode rubric = object(question.rubricJson(), "Coding rubric");
        if (rubric.isEmpty()) throw new IllegalArgumentException("Coding rubric must not be empty");
        rubric.fields().forEachRemaining(field -> {
            JsonNode weight = field.getValue();
            if (!weight.isIntegralNumber() || weight.intValue() < 0 || weight.intValue() > 100) {
                throw new IllegalArgumentException("Coding rubric weights must be integers from 0 to 100");
            }
        });
    }

    private static void validateReferenceConcepts(String raw) {
        if (raw == null || raw.isBlank()) return;
        JsonNode concepts = parse(raw, "referenceConcepts");
        if (!concepts.isArray()) throw new IllegalArgumentException("referenceConcepts must be an array");
        for (JsonNode concept : concepts) {
            if (!concept.isTextual() || concept.textValue().isBlank()) {
                throw new IllegalArgumentException("referenceConcepts must contain non-empty strings");
            }
        }
    }

    private static JsonNode object(String raw, String field) {
        JsonNode value = parse(raw, field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private static JsonNode parse(String raw, String field) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(field + " is required");
        try {
            return MAPPER.readTree(raw);
        } catch (Exception error) {
            throw new IllegalArgumentException(field + " must be valid JSON", error);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Option " + field + " is required");
        return value.textValue().trim();
    }
}
