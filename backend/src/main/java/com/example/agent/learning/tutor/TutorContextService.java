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

/** 根据最新持久化学习事实构建不可变、只读的 Tutor 上下文。 */
@Service
public class TutorContextService {

    private final LearningRepository repository;

    public TutorContextService(LearningRepository repository) {
        this.repository = repository;
    }

    /** 根据 Tutor Session 关联找到 Journey 和 LearnUnit；没有关联时返回空上下文。 */
    public TutorContext forSession(String sessionId) {
        return repository.findTutorSessionBySessionId(sessionId)
                .map(link -> context(link.journeyId(), link.learnUnitCode()))
                .orElseGet(TutorContext::empty);
    }

    public String promptForSession(String sessionId) {
        return forSession(sessionId).systemPrompt();
    }

    /**
     * 汇总 Journey、学习者画像、当前路径、已掌握内容和薄弱点，生成一次 Tutor 调用所需的上下文。
     *
     * <p>所有事实都从 SQLite 读取；本方法只组装提示词数据，不修改学习状态。</p>
     */
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
