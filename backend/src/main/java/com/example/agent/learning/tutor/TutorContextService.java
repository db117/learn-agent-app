package com.example.agent.learning.tutor;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds an immutable, read-only context from the latest durable learning facts. */
@Service
public class TutorContextService {

    private final LearningRepository repository;

    public TutorContextService(LearningRepository repository) {
        this.repository = repository;
    }

    public TutorContext forSession(String sessionId) {
        return repository.findTutorSessionBySessionId(sessionId)
                .map(link -> context(link.journeyId(), link.learnUnitCode()))
                .orElseGet(TutorContext::empty);
    }

    public String promptForSession(String sessionId) {
        return forSession(sessionId).systemPrompt();
    }

    private TutorContext context(String journeyId, String learnUnitCode) {
        LearningJourney journey = repository.findJourney(journeyId).orElse(null);
        LearnerProfile profile = repository.findProfile(journeyId).orElse(null);
        LearnUnit current = repository.findLearnUnit(learnUnitCode).orElse(null);
        LearningPathItem currentPath = repository.findPathItem(journeyId, learnUnitCode).orElse(null);
        List<LearningPathItem> path = repository.listPath(journeyId);
        Map<String, LearnUnit> unitsByCode = repository.listLearnUnitsForJourney(journeyId).stream()
                .collect(Collectors.toMap(LearnUnit::code, Function.identity()));
        LearningPathItem nextPath = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .findFirst()
                .orElse(null);
        LearnUnit next = nextPath == null ? null : unitsByCode.get(nextPath.learnUnitCode());
        List<String> mastered = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .map(item -> {
                    LearnUnit unit = unitsByCode.get(item.learnUnitCode());
                    return unit == null ? item.learnUnitCode() : unit.name();
                })
                .toList();
        List<String> weakPoints = repository.listQuestionAttemptsForLearnUnit(journeyId, learnUnitCode).stream()
                .filter(attempt -> attempt.correct() == null || !attempt.correct())
                .map(attempt -> attempt.feedback())
                .filter(feedback -> feedback != null && !feedback.isBlank())
                .distinct()
                .limit(5)
                .toList();
        TutorContext.MasterySummary mastery = currentPath == null
                ? TutorContext.MasterySummary.empty()
                : new TutorContext.MasterySummary(
                        currentPath.masteryScore(), currentPath.bestAssessmentScore(), currentPath.attemptCount(), mastered);
        String nextStep = next == null
                ? currentPath != null && currentPath.status() != LearningPathItemStatus.CURRENT
                ? "The Journey is complete; review the mastered LearnUnits."
                : "Complete the current LearnUnit assessment."
                : "Complete the current LearnUnit, then continue with " + next.name() + ".";
        return new TutorContext(
                journeyId,
                journey == null ? current == null ? "unknown" : current.languageCode() : journey.languageCode(),
                profile, current, mastery, weakPoints, next, nextStep);
    }
}
