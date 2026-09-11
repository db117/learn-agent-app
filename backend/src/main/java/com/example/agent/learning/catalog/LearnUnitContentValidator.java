package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionRole;
import com.example.agent.learning.assessment.QuestionStructureValidator;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Java-owned bounds for model-generated LearnUnit content. */
public final class LearnUnitContentValidator {

    private LearnUnitContentValidator() {
    }

    public static void validate(LearnUnit outline, LearnUnit content, List<Question> questions) {
        if (outline == null || content == null || !sameOutline(outline, content)) {
            throw new IllegalStateException("Generated LearnUnit content does not match the outline");
        }
        text(content.ability(), "ability", 200);
        if (content.estimatedMinutes() < 1 || content.estimatedMinutes() > 30) {
            throw new IllegalStateException("LearnUnit estimated duration must be between 1 and 30 minutes");
        }
        text(content.lessonIntro(), "lessonIntro", 2000);
        if (content.examples().isEmpty() || content.examples().size() > 5
                || content.examples().stream().anyMatch(value -> value.length() > 2000)) {
            throw new IllegalStateException("LearnUnit examples must contain 1 to 5 bounded items");
        }
        text(content.guidedPracticePrompt(), "guidedPracticePrompt", 1000);
        if (content.guidedPracticeHints().size() > 3
                || content.guidedPracticeHints().stream().anyMatch(value -> value.length() > 300)) {
            throw new IllegalStateException("LearnUnit guided practice hints are too large");
        }
        text(content.independentCheckPrompt(), "independentCheckPrompt", 1000);
        if (questions == null || questions.isEmpty() || questions.size() > 5) {
            throw new IllegalStateException("LearnUnit independent check must contain 1 to 5 questions");
        }
        Set<String> questionIds = new HashSet<>();
        for (Question question : questions) {
            if (question == null || !questionIds.add(question.id())) {
                throw new IllegalStateException("LearnUnit independent check questions must have unique ids");
            }
            QuestionStructureValidator.validate(question, content);
            if (question.role() != QuestionRole.INDEPENDENT || question.diagnosticEligible()) {
                throw new IllegalStateException("Independent check question must be independent: " + question.id());
            }
        }
    }

    private static void text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalStateException("LearnUnit " + field + " is missing or too long");
        }
    }

    private static boolean sameOutline(LearnUnit outline, LearnUnit content) {
        return Objects.equals(outline.id(), content.id())
                && Objects.equals(outline.languageCode(), content.languageCode())
                && Objects.equals(outline.code(), content.code())
                && Objects.equals(outline.chapterCode(), content.chapterCode())
                && Objects.equals(outline.name(), content.name())
                && Objects.equals(outline.description(), content.description())
                && outline.sequence() == content.sequence()
                && Objects.equals(outline.prerequisiteLearnUnitCodes(), content.prerequisiteLearnUnitCodes())
                && outline.passScore() == content.passScore()
                && Objects.equals(outline.minCodingScore(), content.minCodingScore())
                && outline.enabled() == content.enabled()
                && Objects.equals(outline.learningObjectives(), content.learningObjectives())
                && Objects.equals(outline.keyConcepts(), content.keyConcepts())
                && outline.diagnosticEligible() == content.diagnosticEligible();
    }
}
