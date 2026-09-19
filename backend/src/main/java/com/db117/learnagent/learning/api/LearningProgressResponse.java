package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;

import java.util.List;
import java.util.Objects;

/** Learning Session 使用的稳定进度投影；PracticeEvidence 是当前课程的完成依据。 */
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
        /** 当前 LearnUnit 的 Concept/Example 内容快照；路径完成后为空。 */
        String currentLearnUnitContent,
        /** 当前项的最佳评估分数。 */
        int currentMasteryScore,
        /** 当前项的历史最高评估分数。 */
        int currentBestScore,
        /** 当前项是否已有通过的 PracticeEvidence。 */
        boolean practiceVerified,
        /** 已完成的路径项数量。 */
        int completedCount,
        /** 路径项总数。 */
        int totalCount,
        /** 按课程顺序投影的章节和单元状态；UI 不需要读取 Agent State。 */
        List<ChapterProgress> chapters) {

    public static LearningProgressResponse from(long journeyId, LearningJourney journey) {
        Objects.requireNonNull(journey, "journey must not be null");
        var currentItem = journey.currentItem();
        var currentUnit = currentItem == null ? null : journey.learnUnit(currentItem.learnUnitCode());
        var completedCount = (int) journey.pathItems().stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .count();
        var chapters = journey.chapters().stream()
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
                currentItem == null ? 0 : currentItem.masteryScore(),
                currentItem == null ? 0 : currentItem.bestScore(),
                currentItem != null && currentItem.practiceVerified(),
                completedCount,
                journey.pathItems().size(),
                chapters);
    }

    /** 一个章节在当前 LearningJourney 中的只读路径投影。 */
    public record ChapterProgress(
            /** 章节编码。 */
            String code,
            /** 章节标题。 */
            String title,
            /** 章节内按顺序排列的单元状态。 */
            List<UnitProgress> units) {
        public ChapterProgress {
            units = List.copyOf(units == null ? List.of() : units);
        }
    }

    /** 一个 LearnUnit 在当前 Journey 中的只读状态。 */
    public record UnitProgress(
            /** LearnUnit 编码。 */
            String code,
            /** LearnUnit 标题。 */
            String title,
            /** PENDING、CURRENT、COMPLETED 或 SKIPPED。 */
            String status,
            /** 是否已有通过的 PracticeEvidence。 */
            boolean practiceVerified) {
    }
}
