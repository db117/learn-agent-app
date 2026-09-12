package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.Question;

import java.util.List;

/** Safe projection of generated diagnostic questions; answer keys stay server-side. */
public record DiagnosticQuestionPreview(
        int questionCount,
        List<QuestionStem> questions) {

    public DiagnosticQuestionPreview {
        questions = List.copyOf(questions == null ? List.of() : questions);
    }

    public static DiagnosticQuestionPreview from(List<Question> questions) {
        List<QuestionStem> stems = questions == null ? List.of() : questions.stream()
                .filter(java.util.Objects::nonNull)
                .map(question -> new QuestionStem(
                        question.learnUnitCode(), question.type().name(), safeStem(question.prompt())))
                .toList();
        return new DiagnosticQuestionPreview(stems.size(), stems);
    }

    private static String safeStem(String value) {
        if (value == null || value.isBlank()) return "题干已生成。";
        return value.length() <= 240 ? value : value.substring(0, 240) + "…";
    }

    public record QuestionStem(String learnUnitCode, String type, String stem) {
    }
}
