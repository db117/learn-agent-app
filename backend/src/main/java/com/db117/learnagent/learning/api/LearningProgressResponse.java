package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;

import java.util.List;
import java.util.Objects;

/** Learning Session 使用的稳定进度投影；不暴露 Assessment 的正确答案。 */
public record LearningProgressResponse(
        /** URL 中父 Journey 的主键。 */
        long journeyId,
        /** 已确认 LearningJourney 的主键。 */
        long learningJourneyId,
        /** LearningJourney 的 ACTIVE/COMPLETED 状态。 */
        String status,
        /** 当前 LearnUnit 编码；路径完成后为空。 */
        String currentLearnUnitCode,
        /** 当前 LearnUnit 标题；路径完成后为空。 */
        String currentLearnUnitTitle,
        /** 当前 LearnUnit 目标；路径完成后为空。 */
        String currentLearnUnitObjective,
        /** 当前项的最佳评估分数。 */
        int currentMasteryScore,
        /** 当前项的历史最高评估分数。 */
        int currentBestScore,
        /** 当前项是否已有通过的 PracticeEvidence。 */
        boolean practiceVerified,
        /** 当前项是否已有达到及格线的 Assessment。 */
        boolean assessmentPassed,
        /** 已完成的路径项数量。 */
        int completedCount,
        /** 路径项总数。 */
        int totalCount,
        /** 当前项的公开评估题目；路径完成后为空。 */
        AssessmentView assessment) {

    public static LearningProgressResponse from(long journeyId, LearningJourney journey) {
        Objects.requireNonNull(journey, "journey must not be null");
        var currentItem = journey.currentItem();
        var currentUnit = currentItem == null ? null : journey.learnUnit(currentItem.learnUnitCode());
        var assessment = currentItem == null ? null : journey.assessmentFor(currentItem.learnUnitCode());
        var completedCount = (int) journey.pathItems().stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .count();
        return new LearningProgressResponse(
                journeyId,
                Objects.requireNonNull(journey.id(), "persisted learning journey id must not be null"),
                journey.status().name(),
                currentItem == null ? null : currentItem.learnUnitCode(),
                currentUnit == null ? null : currentUnit.title(),
                currentUnit == null ? null : currentUnit.objective(),
                currentItem == null ? 0 : currentItem.masteryScore(),
                currentItem == null ? 0 : currentItem.bestScore(),
                currentItem != null && currentItem.practiceVerified(),
                currentItem != null && currentItem.assessmentPassed(),
                completedCount,
                journey.pathItems().size(),
                assessment == null ? null : AssessmentView.from(assessment));
    }

    /** 当前 Assessment 的公开定义；故意不返回 correctOptionIds。 */
    public record AssessmentView(
            /** 评估对应的 LearnUnit 编码。 */
            String learnUnitCode,
            /** 固定及格分数。 */
            int passingScore,
            /** 评估问题。 */
            List<QuestionView> questions) {
        static AssessmentView from(com.db117.learnagent.learning.domain.Assessment assessment) {
            return new AssessmentView(
                    assessment.learnUnitCode(),
                    assessment.passingScore(),
                    assessment.questions().stream().map(QuestionView::from).toList());
        }
    }

    /** 面向 UI 的问题投影。 */
    public record QuestionView(
            /** 问题编码。 */
            String code,
            /** SINGLE_CHOICE、MULTIPLE_CHOICE 或 CODE。 */
            String type,
            /** 问题文本。 */
            String prompt,
            /** 选择题选项；CODE 为空。 */
            List<String> optionIds) {
        static QuestionView from(com.db117.learnagent.learning.domain.Question question) {
            return new QuestionView(
                    question.code(), question.type().name(), question.prompt(), question.optionIds());
        }
    }
}
