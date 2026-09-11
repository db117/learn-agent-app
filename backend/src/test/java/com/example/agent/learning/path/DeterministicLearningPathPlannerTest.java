package com.example.agent.learning.path;

import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.PassReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicLearningPathPlannerTest {

    @Test
    void usesLearningPathItemsAsTheOnlyPersistedProgressState() {
        LearnUnit advanced = learnUnit("unit-b", 2, List.of("unit-a"));
        LearnUnit basics = learnUnit("unit-a", 1, List.of());
        Chapter chapter = new Chapter("chapter", "chapter", "Chapter", "Chapter goal", 1, List.of());
        LearningPathItem completed = new LearningPathItem(
                "path-a", "journey", "unit-a", 1, LearningPathItemStatus.COMPLETED,
                90, 90, 1, PassReason.DIAGNOSTIC, Instant.EPOCH, Instant.EPOCH, null);

        List<LearningPathItem> path = new DeterministicLearningPathPlanner().plan(
                "journey", List.of(chapter), List.of(advanced, basics), List.of(completed));

        assertEquals(List.of("unit-a", "unit-b"), path.stream().map(LearningPathItem::learnUnitCode).toList());
        assertEquals(LearningPathItemStatus.COMPLETED, path.get(0).status());
        assertEquals(90, path.get(0).masteryScore());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(1).status());
        assertEquals(1, path.stream().filter(item -> item.status() == LearningPathItemStatus.CURRENT).count());
    }

    @Test
    void ordersChapterPrerequisitesBeforeTheirLearnUnits() {
        Chapter basics = new Chapter("chapter-a", "basics", "Basics", "Foundation", 2, List.of());
        Chapter advanced = new Chapter("chapter-b", "advanced", "Advanced", "Application", 1, List.of("basics"));
        LearnUnit advancedUnit = learnUnit("advanced-unit", 1, List.of("basics-unit"), "advanced");
        LearnUnit basicsUnit = learnUnit("basics-unit", 1, List.of(), "basics");

        List<LearningPathItem> path = new DeterministicLearningPathPlanner().plan(
                "journey", List.of(advanced, basics), List.of(advancedUnit, basicsUnit), List.of());

        assertEquals(List.of("basics-unit", "advanced-unit"),
                path.stream().map(LearningPathItem::learnUnitCode).toList());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(0).status());
    }

    private LearnUnit learnUnit(String code, int sequence, List<String> prerequisites) {
        return learnUnit(code, sequence, prerequisites, "chapter");
    }

    private LearnUnit learnUnit(String code, int sequence, List<String> prerequisites, String chapterCode) {
        return new LearnUnit(
                code, "typescript", code, chapterCode, code, "description", sequence, prerequisites, 80, null,
                true, List.of("objective"), "", List.of("concept"), List.of(), false);
    }
}
