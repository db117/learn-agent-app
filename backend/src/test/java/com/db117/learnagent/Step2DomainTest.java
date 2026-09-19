package com.db117.learnagent;

import com.db117.learnagent.learning.domain.*;
import com.db117.learnagent.practice.domain.*;
import com.db117.learnagent.project.domain.*;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Step2DomainTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void journeyBuildsStableTopoPathAndCompletesSequentially() {
        var journey = journey().withId(100);

        assertEquals(List.of("variables", "loops"), journey.pathItems().stream()
                .map(item -> item.learnUnitCode()).toList());
        assertEquals(LearningPathItemStatus.CURRENT, journey.currentItem().status());

        var afterFirst = pass(journey, "variables", 3);
        assertEquals(LearningJourneyStatus.ACTIVE, afterFirst.status());
        assertEquals("loops", afterFirst.currentItem().learnUnitCode());
        assertEquals(80, afterFirst.pathItems().get(0).masteryScore());

        var completed = pass(afterFirst, "loops", 7);
        assertEquals(LearningJourneyStatus.COMPLETED, completed.status());
        assertEquals(LearningPathItemStatus.COMPLETED, completed.pathItems().get(1).status());
    }

    @Test
    void practiceEvidenceCompletesTheCurrentLearnUnit() {
        var journey = journey().withId(101);
        var afterPractice = journey.recordPracticeVerified("variables", T0.plusSeconds(1));

        assertEquals(LearningPathItemStatus.COMPLETED, afterPractice.pathItems().get(0).status());
        assertFalse(afterPractice.pathItems().get(0).assessmentPassed());
        assertEquals("PRACTICE_EVIDENCE", afterPractice.pathItems().get(0).passReason());
        assertEquals(new Mastery(0, true), afterPractice.pathItems().get(0).mastery());
        assertEquals("loops", afterPractice.currentItem().learnUnitCode());
    }

    @Test
    void lockedPathCannotActivateALaterLearnUnit() {
        var journey = journey().withId(101);

        assertThrows(DomainRuleViolation.class,
                () -> journey.activate("loops", T0.plusSeconds(1)));
    }

    @Test
    void cyclicPrerequisitesAreRejected() {
        var chapter = Chapter.create("basics", "Basics", 0);
        var first = LearnUnit.create("a", "A", "A", "A", 0, "basics", Set.of("b"));
        var second = LearnUnit.create("b", "B", "B", "B", 1, "basics", Set.of("a"));
        var assessments = List.of(assessment("a"), assessment("b"));

        assertThrows(DomainRuleViolation.class, () -> LearningJourney.create(
                1, "java", "cycle", List.of(chapter), List.of(first, second), assessments, T0));
    }

    @Test
    void practiceKeepsFailedEvidenceAndStopsAfterVerification() {
        var policy = new VerificationPolicy(true, true, false, false);
        var task = PracticeTask.create(
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
        var failed = new PracticeEvidence(
                true, true, 0, false, RuntimeResult.NOT_RUN, List.of("src/Main.java"), T0.plusSeconds(1));
        var open = task.recordAttempt(PracticeAttempt.submit(failed, T0.plusSeconds(1)));
        assertEquals(PracticeTaskStatus.OPEN, open.status());

        var passed = new PracticeEvidence(
                true, true, 1, false, RuntimeResult.NOT_RUN, List.of("src/Main.java"), T0.plusSeconds(2));
        var verified = open.recordAttempt(PracticeAttempt.submit(passed, T0.plusSeconds(2)));
        assertEquals(PracticeTaskStatus.VERIFIED, verified.status());
        assertEquals(2, verified.attempts().size());
        assertThrows(DomainRuleViolation.class,
                () -> verified.recordAttempt(PracticeAttempt.submit(passed, T0.plusSeconds(3))));
    }

    @Test
    void projectCompletesOnlyAfterPassingMilestoneEvidence() {
        var project = Project.create(
                100,
                "Todo app",
                List.of(ProjectMilestone.create("m1", "First milestone", 0)),
                T0);
        assertThrows(DomainRuleViolation.class, () -> project.startMilestone("m1"));

        var active = project.activate().startMilestone("m1");
        var failed = active.recordEvidence(
                "m1",
                new ProjectEvidence("workspace://todo", "tests failed", false, T0.plusSeconds(1)));
        assertEquals(ProjectStatus.ACTIVE, failed.status());
        assertEquals(ProjectMilestoneStatus.IN_PROGRESS, failed.milestones().get(0).status());

        var completed = failed.recordEvidence(
                "m1",
                new ProjectEvidence("workspace://todo", "tests passed", true, T0.plusSeconds(2)));
        assertEquals(ProjectStatus.COMPLETED, completed.status());
        assertEquals(ProjectMilestoneStatus.COMPLETED, completed.milestones().get(0).status());
        assertEquals(T0.plusSeconds(2), completed.completedAt());
    }

    private LearningJourney pass(LearningJourney journey, String learnUnitCode, long offset) {
        var attempt = AssessmentAttempt.submitted(
                        journey.id(),
                        learnUnitCode,
                        List.of(Answer.choice("choice", Set.of("yes"))),
                        T0.plusSeconds(offset))
                .evaluate(80, T0.plusSeconds(offset + 1));
        return journey.recordAssessmentAttempt(attempt)
                .recordPracticeVerified(learnUnitCode, T0.plusSeconds(offset + 2));
    }

    private LearningJourney journey() {
        var chapter = Chapter.create("basics", "Basics", 0);
        var variables = LearnUnit.create(
                "variables", "Variables", "Use values", "Variables content", 0, "basics", Set.of());
        var loops = LearnUnit.create(
                "loops", "Loops", "Repeat work", "Loops content", 1, "basics", Set.of("variables"));
        return LearningJourney.create(
                11,
                "java",
                "Java Journey",
                List.of(chapter),
                List.of(loops, variables),
                List.of(assessment("variables"), assessment("loops")),
                T0);
    }

    private Assessment assessment(String learnUnitCode) {
        return Assessment.create(
                learnUnitCode,
                70,
                List.of(Question.singleChoice("choice", "Choose yes", List.of("yes", "no"), "yes")));
    }
}
