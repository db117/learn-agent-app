package com.example.agent.learning.path;

import com.example.agent.learning.journey.PassReason;

import java.time.Instant;
import java.util.List;

/**
 * Journey Path 中的一个 LearnUnit 节点。
 *
 * @param id Path 节点主键
 * @param journeyId 所属 Journey
 * @param learnUnitCode 节点对应的 LearnUnit 编码
 * @param sequence 当前 Path 中的展示顺序
 * @param status 节点状态；历史节点仍保留在 Path 中
 * @param masteryScore 历史最高掌握度
 * @param bestAssessmentScore 历史最高评估分数
 * @param attemptCount 已完成的评估次数
 * @param passReason 通过来源；跳过不会设置该字段
 * @param startedAt 首次开始该 LearnUnit 的时间
 * @param passedAt 最近一次通过时间
 * @param skippedAt 跳过时间
 * @param learningPhase 当前教学阶段
 * @param skippedPhases 已显式跳过的教学阶段
 * @param guidedPracticeEntries 不计分的引导练习记录
 */
public record LearningPathItem(
        String id,
        String journeyId,
        String learnUnitCode,
        int sequence,
        LearningPathItemStatus status,
        int masteryScore,
        int bestAssessmentScore,
        int attemptCount,
        PassReason passReason,
        Instant startedAt,
        Instant passedAt,
        Instant skippedAt,
        LearningPhase learningPhase,
        List<LearningPhase> skippedPhases,
        List<GuidedPracticeEntry> guidedPracticeEntries) {

    public LearningPathItem {
        learningPhase = learningPhase == null ? LearningPhase.EXPLANATION : learningPhase;
        skippedPhases = List.copyOf(skippedPhases == null ? List.of() : skippedPhases);
        guidedPracticeEntries = List.copyOf(guidedPracticeEntries == null ? List.of() : guidedPracticeEntries);
    }

    public LearningPathItem(
            String id,
            String journeyId,
            String learnUnitCode,
            int sequence,
            LearningPathItemStatus status) {
        this(id, journeyId, learnUnitCode, sequence, status, 0, 0, 0, null, null, null, null,
                LearningPhase.EXPLANATION, List.of(), List.of());
    }

    public LearningPathItem(
            String id,
            String journeyId,
            String learnUnitCode,
            int sequence,
            LearningPathItemStatus status,
            int masteryScore,
            int bestAssessmentScore,
            int attemptCount,
            PassReason passReason,
            Instant startedAt,
            Instant passedAt,
            Instant skippedAt) {
        this(id, journeyId, learnUnitCode, sequence, status, masteryScore, bestAssessmentScore, attemptCount,
                passReason, startedAt, passedAt, skippedAt, LearningPhase.EXPLANATION, List.of(), List.of());
    }
}
