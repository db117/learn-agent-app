package com.example.agent.learning.tutor;

import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearnerSkill;
import com.example.agent.learning.persistence.LearningRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 为既有 Tutor Session 组装只读学习上下文。
 *
 * <p>上下文来自 Journey、画像、当前技能和历史弱点反馈；Tutor 只能据此教学，不能直接改写学习状态。
 */
@Service
public class TutorContextService {

    private final LearningRepository repository;

    public TutorContextService(LearningRepository repository) {
        this.repository = repository;
    }

    public String forSession(String sessionId) {
        return repository.findTutorSessionBySessionId(sessionId)
                .map(link -> context(link.journeyId(), link.skillCode()))
                .orElse(staticInstructions());
    }

    private String context(String journeyId, String skillCode) {
        LearnerProfile profile = repository.findProfile(journeyId).orElse(null);
        LearningSkill skill = repository.findSkill(skillCode).orElse(null);
        LearnerSkill learnerSkill = repository.findLearnerSkill(journeyId, skillCode).orElse(null);
        List<String> weakPoints = repository.listQuestionAttemptsForSkill(journeyId, skillCode).stream()
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
                Current skill: %s
                Learning objectives: %s
                Mastery score: %s
                Known weak points: %s
                Use the learner's background when explaining concepts and suggest the Learning Engine actions when appropriate.
                """.formatted(
                skill == null ? "unknown" : skill.languageCode(), background,
                profile == null ? "not provided" : profile.learningGoal(),
                skill == null ? skillCode : skill.name(),
                skill == null ? List.of() : skill.learningObjectives(),
                learnerSkill == null ? 0 : learnerSkill.masteryScore(), weakPoints);
    }

    private String staticInstructions() {
        return "You are TutorAgent. Help the user learn programming clearly and patiently. "
                + "You teach only; the Learning Engine controls scores, pass/fail, skip, and path state.";
    }
}
