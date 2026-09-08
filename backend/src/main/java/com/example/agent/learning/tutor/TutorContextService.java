package com.example.agent.learning.tutor;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearnerLearnUnit;
import com.example.agent.learning.persistence.LearningRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 为 Tutor Session 组装只读学习上下文。
 *
 * <p>上下文来自 Journey、画像、当前 LearnUnit 和历史弱点反馈；Tutor 只能据此教学，不能直接改写学习状态。
 */
@Service
public class TutorContextService {

    private final LearningRepository repository;

    public TutorContextService(LearningRepository repository) {
        this.repository = repository;
    }

    public String forSession(String sessionId) {
        return repository.findTutorSessionBySessionId(sessionId)
                .map(link -> context(link.journeyId(), link.learnUnitCode()))
                .orElse(staticInstructions());
    }

    private String context(String journeyId, String learnUnitCode) {
        LearnerProfile profile = repository.findProfile(journeyId).orElse(null);
        LearnUnit learnUnit = repository.findLearnUnit(learnUnitCode).orElse(null);
        LearnerLearnUnit learnerLearnUnit = repository.findLearnerLearnUnit(journeyId, learnUnitCode).orElse(null);
        List<String> weakPoints = repository.listQuestionAttemptsForLearnUnit(journeyId, learnUnitCode).stream()
                .filter(value -> value.correct() == null || !value.correct())
                .map(value -> value.feedback())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(5)
                .toList();
        String background = profile == null
                ? "not provided"
                : profile.primaryLanguage() + ", " + (profile.experienceYears() == null ? "experience unknown" : profile.experienceYears() + " years")
                        + "; " + profile.selfDescription();
        return """
                You are TutorAgent. Teach the learner, but never modify learning progress, scores, pass/fail, or path state.
                Learning context:
                Target language: %s
                Learner background: %s
                Learning goal: %s
                Current LearnUnit: %s
                Learning objectives: %s
                Mastery score: %s
                Known weak points: %s
                Use the learner's background when explaining concepts and suggest the Learning Engine actions when appropriate.
                """.formatted(
                learnUnit == null ? "unknown" : learnUnit.languageCode(), background,
                profile == null ? "not provided" : profile.learningGoal(),
                learnUnit == null ? learnUnitCode : learnUnit.name(),
                learnUnit == null ? List.of() : learnUnit.learningObjectives(),
                learnerLearnUnit == null ? 0 : learnerLearnUnit.masteryScore(), weakPoints);
    }

    private String staticInstructions() {
        return "You are TutorAgent. Help the user learn programming clearly and patiently. "
                + "You teach only; the Learning Engine controls scores, pass/fail, skip, and path state.";
    }
}
