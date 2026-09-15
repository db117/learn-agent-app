package com.example.agent.llm.contract;

import com.example.agent.learning.assessment.QuestionType;

import java.util.List;

/** LLM provider 边界的固定 JSON 契约；进入 Learning Engine 前会映射为领域对象。 */
public final class LlmContracts {

    private LlmContracts() {
    }

    public record Outline(
            List<Language> languages,
            List<Chapter> chapters,
            List<LearnUnit> learnUnits) {
    }

    public record Language(String code, String name, String description) {
    }

    public record Chapter(
            String code,
            String name,
            String goal,
            Integer sequence,
            List<String> prerequisiteChapterCodes) {
    }

    public record LearnUnit(
            String languageCode,
            String chapterCode,
            String code,
            String name,
            String description,
            Integer sequence,
            List<String> prerequisiteLearnUnitCodes,
            Integer passScore,
            Integer minCodingScore,
            List<String> learningObjectives,
            List<String> keyConcepts) {
    }

    public record Content(
            String ability,
            Integer estimatedMinutes,
            String lessonIntro,
            List<String> examples,
            String guidedPracticePrompt,
            List<String> guidedPracticeHints,
            String independentCheckPrompt,
            List<Question> questions) {
    }

    public record DiagnosticPlan(List<Question> questions) {
    }

    /** LearnUnit 内容和诊断规划共用的题目输入结构。 */
    public record Question(
            String existingQuestionId,
            String learnUnitCode,
            QuestionType type,
            Integer difficulty,
            String prompt,
            Integer points,
            List<Option> options,
            List<String> correctOptionIds,
            Boolean multiple,
            String language,
            String starterCode,
            Rubric rubric,
            List<String> referenceConcepts) {
    }

    public record Option(String id, String text) {
    }

    public record Rubric(Integer correctness, Integer languageUsage, Integer clarity) {
    }

    public record CodingEvaluation(
            Integer correctness,
            Integer languageUsage,
            Integer clarity,
            String feedback,
            List<String> issues) {
    }
}
