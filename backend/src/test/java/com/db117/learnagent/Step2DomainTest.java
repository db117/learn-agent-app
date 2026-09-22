package com.db117.learnagent;

import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyStatus;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
import com.db117.learnagent.learning.domain.Mastery;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskStatus;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.project.domain.Project;
import com.db117.learnagent.project.domain.ProjectEvidence;
import com.db117.learnagent.project.domain.ProjectMilestone;
import com.db117.learnagent.project.domain.ProjectMilestoneStatus;
import com.db117.learnagent.project.domain.ProjectStatus;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Step2DomainTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void journeyBuildsStableTopoPathAndCompletesSequentially() {
        LearningJourney journey = journey().withId(100);

        assertEquals(List.of("variables", "loops"), journey.pathItems().stream()
                .map(item -> item.learnUnitCode()).toList());
        assertEquals(LearningPathItemStatus.CURRENT, journey.currentItem().status());

        LearningJourney afterFirst = journey.recordPracticeVerified("variables", T0.plusSeconds(3));
        assertEquals(LearningJourneyStatus.ACTIVE, afterFirst.status());
        assertEquals("loops", afterFirst.currentItem().learnUnitCode());

        LearningJourney completed = afterFirst.recordPracticeVerified("loops", T0.plusSeconds(7));
        assertEquals(LearningJourneyStatus.COMPLETED, completed.status());
        assertEquals(LearningPathItemStatus.COMPLETED, completed.pathItems().get(1).status());
    }

    @Test
    void practiceEvidenceCompletesTheCurrentLearnUnit() {
        LearningJourney journey = journey().withId(101);
        LearningJourney afterPractice = journey.recordPracticeVerified("variables", T0.plusSeconds(1));

        assertEquals(LearningPathItemStatus.COMPLETED, afterPractice.pathItems().get(0).status());
        assertTrue(afterPractice.pathItems().get(0).practiceVerified());
        assertEquals("PRACTICE_EVIDENCE", afterPractice.pathItems().get(0).passReason());
        assertEquals(new Mastery(true), afterPractice.pathItems().get(0).mastery());
        assertEquals("loops", afterPractice.currentItem().learnUnitCode());
    }

    @Test
    void lockedPathCannotActivateALaterLearnUnit() {
        LearningJourney journey = journey().withId(101);

        assertThrows(DomainRuleViolation.class,
                () -> journey.activate("loops", T0.plusSeconds(1)));
    }

    @Test
    void cyclicPrerequisitesAreRejected() {
        Chapter chapter = Chapter.create("basics", "Basics", 0);
        LearnUnit first = LearnUnit.create("a", "A", "A", "A", 0, "basics", Set.of("b"));
        LearnUnit second = LearnUnit.create("b", "B", "B", "B", 1, "basics", Set.of("a"));
        assertThrows(DomainRuleViolation.class, () -> LearningJourney.create(
                1, "java", "cycle", List.of(chapter), List.of(first, second), T0));
    }

    @Test
    void outlineLearnUnitCanReceiveContentWhenLearningStarts() {
        LearnUnit outline = LearnUnit.create("a", "A", "Learn A", "", 0, "basics", Set.of());

        LearnUnit materialized = outline.withContent("## Concept\nA concept\n## Practice\nDo A");

        assertEquals("", outline.content());
        assertEquals("Do A", materialized.practiceInstruction());
    }

    @Test
    void learningJourneyCanPersistContentForItsCurrentUnit() {
        LearningJourney journey = journey().withId(101);

        LearningJourney materialized = journey.materializeLearnUnitContent(
                "variables", "## Concept\nVariables\n## Practice\nFix variables");

        assertEquals("## Concept\nVariables\n## Practice\nFix variables",
                materialized.learnUnit("variables").content());
        assertEquals("Loops content", materialized.learnUnit("loops").content());
    }

    @Test
    void practiceKeepsFailedEvidenceAndStopsAfterVerification() {
        VerificationPolicy policy = new VerificationPolicy(true, true, false, false);
        PracticeTask task = PracticeTask.create(
                100,
                200,
                "java",
                "CODE",
                "Fix it",
                "Make the test pass",
                1,
                "class Main {}",
                policy,
                T0);
        PracticeEvidence failed = new PracticeEvidence(
                true, true, 0, false, RuntimeResult.NOT_RUN, List.of("src/Main.java"), T0.plusSeconds(1));
        PracticeTask open = task.recordAttempt(PracticeAttempt.submit(failed, T0.plusSeconds(1)));
        assertEquals(PracticeTaskStatus.OPEN, open.status());

        PracticeEvidence passed = new PracticeEvidence(
                true, true, 1, false, RuntimeResult.NOT_RUN, List.of("src/Main.java"), T0.plusSeconds(2));
        PracticeTask verified = open.recordAttempt(PracticeAttempt.submit(passed, T0.plusSeconds(2)));
        assertEquals(PracticeTaskStatus.VERIFIED, verified.status());
        assertEquals(2, verified.attempts().size());
        assertThrows(DomainRuleViolation.class,
                () -> verified.recordAttempt(PracticeAttempt.submit(passed, T0.plusSeconds(3))));
    }

    @Test
    void projectCompletesOnlyAfterPassingMilestoneEvidence() {
        Project project = Project.create(
                100,
                "Todo app",
                List.of(ProjectMilestone.create("m1", "First milestone", 0)),
                T0);
        assertThrows(DomainRuleViolation.class, () -> project.startMilestone("m1"));

        Project active = project.activate().startMilestone("m1");
        Project failed = active.recordEvidence(
                "m1",
                new ProjectEvidence("workspace://todo", "tests failed", false, T0.plusSeconds(1)));
        assertEquals(ProjectStatus.ACTIVE, failed.status());
        assertEquals(ProjectMilestoneStatus.IN_PROGRESS, failed.milestones().get(0).status());

        Project completed = failed.recordEvidence(
                "m1",
                new ProjectEvidence("workspace://todo", "tests passed", true, T0.plusSeconds(2)));
        assertEquals(ProjectStatus.COMPLETED, completed.status());
        assertEquals(ProjectMilestoneStatus.COMPLETED, completed.milestones().get(0).status());
        assertEquals(T0.plusSeconds(2), completed.completedAt());
    }

    private LearningJourney journey() {
        Chapter chapter = Chapter.create("basics", "Basics", 0);
        LearnUnit variables = LearnUnit.create(
                "variables", "Variables", "Use values", "Variables content", 0, "basics", Set.of());
        LearnUnit loops = LearnUnit.create(
                "loops", "Loops", "Repeat work", "Loops content", 1, "basics", Set.of("variables"));
        return LearningJourney.create(
                11,
                "java",
                "Java Journey",
                List.of(chapter),
                List.of(loops, variables),
                T0);
    }
}
