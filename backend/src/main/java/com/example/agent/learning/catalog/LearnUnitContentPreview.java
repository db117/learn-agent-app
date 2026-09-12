package com.example.agent.learning.catalog;

import java.util.List;

/** Safe teaching-content projection for the generation progress stream. */
public record LearnUnitContentPreview(
        String ability,
        int estimatedMinutes,
        String lessonIntro,
        List<String> examples,
        String guidedPracticePrompt,
        List<String> guidedPracticeHints,
        String independentCheckPrompt,
        int independentQuestionCount) {

    public LearnUnitContentPreview {
        examples = List.copyOf(examples == null ? List.of() : examples);
        guidedPracticeHints = List.copyOf(guidedPracticeHints == null ? List.of() : guidedPracticeHints);
    }

    public static LearnUnitContentPreview from(
            LearnUnit content, int independentQuestionCount) {
        return new LearnUnitContentPreview(
                content.ability(), content.estimatedMinutes(), content.lessonIntro(), content.examples(),
                content.guidedPracticePrompt(), content.guidedPracticeHints(),
                content.independentCheckPrompt(), independentQuestionCount);
    }
}
