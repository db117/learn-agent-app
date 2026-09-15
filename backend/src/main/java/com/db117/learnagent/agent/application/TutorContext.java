package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;

import java.util.Objects;

/**
 * 每个 Turn 重新装配的只读 Domain 投影；不把 Agent State 反向写回 Learning Domain。
 *
 * @param learnerId 学习者的 Domain ID
 * @param learnerDisplayName 学习者展示名称
 * @param journeyId 当前 LearningJourney 的 Domain ID
 * @param journeyTitle 当前 Journey 标题
 * @param languagePackId 当前 Journey 使用的 Language Pack 标识
 * @param currentLearnUnitCode 当前路径项对应的 LearnUnit 编码
 * @param currentLearnUnitTitle 当前 LearnUnit 标题
 * @param currentObjective 当前 LearnUnit 的学习目标
 * @param currentContent 当前 Journey 内的 LearnUnit 内容快照
 * @param currentStatus 当前路径项状态；活动 Journey 中应为 {@code CURRENT}
 * @param masteryScore 当前路径项的掌握分数
 * @param bestScore 当前路径项的历史最高评估分数
 * @param practiceVerified 当前路径项是否已有通过的 Practice 证据
 * @param assessmentPassed 当前路径项是否已有通过的 Assessment 结果
 * @param attemptCount 当前路径项的评估尝试次数
 * @param completedItemCount 当前 Journey 已完成或跳过的路径项数量
 * @param totalItemCount 当前 Journey 路径项总数
 * @param workspace 当前只读 Runtime 工作区身份
 */
public record TutorContext(
        long learnerId,
        String learnerDisplayName,
        long journeyId,
        String journeyTitle,
        String languagePackId,
        String currentLearnUnitCode,
        String currentLearnUnitTitle,
        String currentObjective,
        String currentContent,
        LearningPathItemStatus currentStatus,
        int masteryScore,
        int bestScore,
        boolean practiceVerified,
        boolean assessmentPassed,
        int attemptCount,
        int completedItemCount,
        int totalItemCount,
        WorkspaceBinding workspace) {

    public TutorContext {
        if (learnerId <= 0 || journeyId <= 0) {
            throw new IllegalArgumentException("learnerId and journeyId must be positive");
        }
        learnerDisplayName = requireText(learnerDisplayName, "learnerDisplayName");
        journeyTitle = requireText(journeyTitle, "journeyTitle");
        languagePackId = requireText(languagePackId, "languagePackId");
        currentLearnUnitCode = requireText(currentLearnUnitCode, "currentLearnUnitCode");
        currentLearnUnitTitle = requireText(currentLearnUnitTitle, "currentLearnUnitTitle");
        currentObjective = requireText(currentObjective, "currentObjective");
        currentContent = requireText(currentContent, "currentContent");
        currentStatus = Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        if (masteryScore < 0 || masteryScore > 100 || bestScore < 0 || bestScore > 100) {
            throw new IllegalArgumentException("scores must be between 0 and 100");
        }
        if (attemptCount < 0 || completedItemCount < 0 || totalItemCount <= 0
                || completedItemCount > totalItemCount) {
            throw new IllegalArgumentException("invalid progress summary");
        }
        workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    }

    /** 返回只读系统上下文；不包含 Session ID、路径、凭据或模型私有状态。 */
    public String asSystemContext() {
        return """
                <tutor-context>
                learner: %s
                journey: %s
                language-pack: %s
                current-learn-unit: %s
                current-title: %s
                objective: %s
                content: %s
                current-status: %s
                mastery-score: %d
                best-score: %d
                practice-verified: %s
                assessment-passed: %s
                assessment-attempts: %d
                journey-progress: %d/%d
                workspace-kind: %s
                workspace-id: %s
                </tutor-context>
                """.formatted(
                learnerDisplayName,
                journeyTitle,
                languagePackId,
                currentLearnUnitCode,
                currentLearnUnitTitle,
                currentObjective,
                currentContent,
                currentStatus,
                masteryScore,
                bestScore,
                practiceVerified,
                assessmentPassed,
                attemptCount,
                completedItemCount,
                totalItemCount,
                workspace.kind(),
                workspace.id());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
