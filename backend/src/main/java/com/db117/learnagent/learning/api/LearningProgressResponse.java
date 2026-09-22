package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;

import java.util.List;
import java.util.Objects;

/**
 * Learning Session 使用的稳定进度投影；PracticeEvidence 是当前课程的完成依据。
 *
 * @param journeyId URL 中父 Journey 的主键
 * @param learningJourneyId 已确认 LearningJourney 的主键
 * @param status LearningJourney 的 ACTIVE/COMPLETED 状态
 * @param currentLearnUnitCode 当前 LearnUnit 编码；路径完成后为空
 * @param currentLearnUnitTitle 当前 LearnUnit 标题；路径完成后为空
 * @param currentLearnUnitObjective 当前 LearnUnit 目标；路径完成后为空
 * @param currentLearnUnitContent 当前 LearnUnit 的 Concept/Example 内容快照；路径完成后为空
 * @param practiceVerified 当前项是否已有通过的 PracticeEvidence
 * @param completedCount 已完成的路径项数量
 * @param totalCount 路径项总数
 * @param chapters 按课程顺序投影的章节和单元状态；UI 不需要读取 Agent State
 */
public record LearningProgressResponse(
        long journeyId,
        long learningJourneyId,
        String status,
        String currentLearnUnitCode,
        String currentLearnUnitTitle,
        String currentLearnUnitObjective,
        String currentLearnUnitContent,
        boolean practiceVerified,
        int completedCount,
        int totalCount,
        List<ChapterProgress> chapters) {

    public static LearningProgressResponse from(long journeyId, LearningJourney journey) {
        Objects.requireNonNull(journey, "journey must not be null");
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = journey.currentItem();
        com.db117.learnagent.learning.domain.LearnUnit currentUnit = currentItem == null ? null : journey.learnUnit(currentItem.learnUnitCode());
        int completedCount = (int) journey.pathItems().stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .count();
        List<LearningProgressResponse.ChapterProgress> chapters = journey.chapters().stream()
                .map(chapter -> new ChapterProgress(
                        chapter.code(),
                        chapter.title(),
                        journey.learnUnits().stream()
                                .filter(unit -> unit.chapterCode().equals(chapter.code()))
                                .map(unit -> journey.pathItems().stream()
                                        .filter(item -> item.learnUnitCode().equals(unit.code()))
                                        .findFirst()
                                        .map(item -> new UnitProgress(
                                                unit.code(), unit.title(), item.status().name(), item.practiceVerified()))
                                        .orElseThrow(() -> new IllegalStateException(
                                                "missing path item for LearnUnit: " + unit.code())))
                                .toList()))
                .toList();
        return new LearningProgressResponse(
                journeyId,
                Objects.requireNonNull(journey.id(), "persisted learning journey id must not be null"),
                journey.status().name(),
                currentItem == null ? null : currentItem.learnUnitCode(),
                currentUnit == null ? null : currentUnit.title(),
                currentUnit == null ? null : currentUnit.objective(),
                currentUnit == null ? null : currentUnit.content(),
                currentItem != null && currentItem.practiceVerified(),
                completedCount,
                journey.pathItems().size(),
                chapters);
    }

    /**
     * 一个章节在当前 LearningJourney 中的只读路径投影。
     *
     * @param code 章节编码
     * @param title 章节标题
     * @param units 章节内按顺序排列的单元状态
     */
    public record ChapterProgress(
            String code,
            String title,
            List<UnitProgress> units) {
        public ChapterProgress {
            units = List.copyOf(units == null ? List.of() : units);
        }
    }

    /**
     * 一个 LearnUnit 在当前 Journey 中的只读状态。
     *
     * @param code LearnUnit 编码
     * @param title LearnUnit 标题
     * @param status PENDING、CURRENT、COMPLETED 或 SKIPPED
     * @param practiceVerified 是否已有通过的 PracticeEvidence
     */
    public record UnitProgress(
            String code,
            String title,
            String status,
            boolean practiceVerified) {
    }
}
