package com.example.agent.learning.tutor;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.path.LearningPhase;

import java.util.List;

/** Immutable, framework-neutral facts supplied to one TutorAgent call. */
public record TutorContext(
        String journeyId,
        String targetLanguage,
        LearnerProfile learnerProfile,
        LearnUnit currentLearnUnit,
        LearningPhase learningPhase,
        MasterySummary mastery,
        List<String> weakPoints,
        String failedAbility,
        boolean remediationNeeded,
        LearnUnit nextLearnUnit,
        String nextStep) {

    public TutorContext {
        weakPoints = List.copyOf(weakPoints == null ? List.of() : weakPoints);
        mastery = mastery == null ? MasterySummary.empty() : mastery;
        failedAbility = failedAbility == null ? "" : failedAbility;
        nextStep = nextStep == null || nextStep.isBlank() ? "Continue the current LearnUnit." : nextStep;
    }

    public static TutorContext empty() {
        return new TutorContext(
                null, "unknown", null, null, null, MasterySummary.empty(), List.of(), "", false, null,
                "Ask the learner what they want to practice.");
    }

    /** Convert the facts to the system-prompt fragment; no mutating tool is exposed. */
    public String systemPrompt() {
        String background = learnerProfile == null
                ? "not provided"
                : learnerProfile.primaryLanguage() + ", "
                + (learnerProfile.experienceYears() == null
                ? "experience unknown"
                : learnerProfile.experienceYears() + " years")
                + "; " + learnerProfile.selfDescription();
        String current = currentLearnUnit == null ? "not selected" : currentLearnUnit.name();
        String next = nextLearnUnit == null ? "none" : nextLearnUnit.name();
        return """
                Learning context (read-only; Learning Engine owns all mutations):
                Target language: %s
                Learner background: %s
                Learning goal: %s
                Current LearnUnit: %s
                Current phase: %s
                Mastery summary: %s
                Known weak points: %s
                Failed ability: %s
                Remediation needed: %s
                Next LearnUnit: %s
                Next step: %s
                Do not modify scores, pass/fail, skip status, or the Learning Path.
                """.formatted(
                targetLanguage, background, learnerProfile == null ? "not provided" : learnerProfile.learningGoal(),
                current, learningPhase == null ? "not started" : learningPhase, mastery, weakPoints,
                failedAbility.isBlank() ? "none" : failedAbility, remediationNeeded, next, nextStep);
    }

    public record MasterySummary(
            int currentScore,
            int bestAssessmentScore,
            int attemptCount,
            List<String> masteredLearnUnits) {

        public MasterySummary {
            masteredLearnUnits = List.copyOf(masteredLearnUnits == null ? List.of() : masteredLearnUnits);
        }

        public static MasterySummary empty() {
            return new MasterySummary(0, 0, 0, List.of());
        }
    }
}
