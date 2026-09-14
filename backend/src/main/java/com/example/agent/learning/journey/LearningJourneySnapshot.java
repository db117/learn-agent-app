package com.example.agent.learning.journey;

import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.LearningPathItem;

import java.util.List;

/** Journey 详情读取结果；只组合事实，不修改学习状态。 */
public record LearningJourneySnapshot(
        LearningJourney journey,
        LearnerProfile profile,
        List<ChapterSnapshot> chapters,
        List<LearningPathItem> path,
        String diagnosticAssessmentId) {

    public LearningJourneySnapshot {
        chapters = List.copyOf(chapters);
        path = List.copyOf(path);
    }

    /** 一个 Chapter 的课程内容和确定性进度摘要。 */
    public record ChapterSnapshot(
            Chapter chapter,
            List<LearnUnit> learnUnits,
            List<LearningPathItem> path,
            int completedCount,
            int skippedCount,
            int unresolvedCount,
            boolean synthesisAvailable,
            boolean synthesisCompleted,
            String synthesisAssessmentId) {

        public ChapterSnapshot {
            learnUnits = List.copyOf(learnUnits);
            path = List.copyOf(path);
        }
    }
}
