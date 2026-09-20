package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;

import java.util.Objects;

/**
 * 每个 Turn 重新装配的只读 Domain 投影；不把 Agent State 反向写回 Learning Domain。
 *
 * @param learnerId Learner 的 Domain ID
 * @param learnerDisplayName Learner 展示名称
 * @param learnerBackgroundSummary Learner 自己确认的背景能力描述
 * @param journeyId Journey 的 Domain ID
 * @param journeyTitle LearningJourney 标题；规划模式尚未生成时为空
 * @param journeyGoalDescription 用户确认的 Journey 目标原文
 * @param languagePackId 当前学习路径使用的 Language Pack；规划模式尚未选择时为空
 * @param currentLearnUnitCode 当前路径项对应的 LearnUnit 编码；规划模式尚未生成时为空
 * @param currentLearnUnitTitle 当前 LearnUnit 标题；规划模式尚未生成时为空
 * @param currentObjective 当前 LearnUnit 学习目标；规划模式尚未生成时为空
 * @param currentContent 当前 LearnUnit 内容快照；规划模式尚未生成时为空
 * @param currentStatus 当前路径项状态；规划模式尚未生成时为空
 * @param masteryScore 当前路径项掌握分数
 * @param bestScore 当前路径项历史最高评估分数
 * @param practiceVerified 当前路径项是否已有通过的 Practice 证据
 * @param completedItemCount 当前 Journey 已完成或跳过的路径项数量
 * @param totalItemCount 当前 Journey 路径项总数
 * @param mode 当前 Session 的规划或学习模式
 * @param workspace 当前只读 Runtime 工作区身份
 */
public record TutorContext(
        long learnerId,
        String learnerDisplayName,
        String learnerBackgroundSummary,
        long journeyId,
        String journeyTitle,
        String journeyGoalDescription,
        String languagePackId,
        String currentLearnUnitCode,
        String currentLearnUnitTitle,
        String currentObjective,
        String currentContent,
        LearningPathItemStatus currentStatus,
        int masteryScore,
        int bestScore,
        boolean practiceVerified,
        int completedItemCount,
        int totalItemCount,
        TutorSessionMode mode,
        WorkspaceBinding workspace) {

    public TutorContext {
        if (learnerId <= 0 || journeyId <= 0) {
            throw new IllegalArgumentException("learnerId and journeyId must be positive");
        }
        learnerDisplayName = requireText(learnerDisplayName, "learnerDisplayName");
        learnerBackgroundSummary = requireText(learnerBackgroundSummary, "learnerBackgroundSummary");
        journeyGoalDescription = requireText(journeyGoalDescription, "journeyGoalDescription");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        if (mode == TutorSessionMode.LEARNING) {
            journeyTitle = requireText(journeyTitle, "journeyTitle");
            languagePackId = requireText(languagePackId, "languagePackId");
            currentLearnUnitCode = requireText(currentLearnUnitCode, "currentLearnUnitCode");
            currentLearnUnitTitle = requireText(currentLearnUnitTitle, "currentLearnUnitTitle");
            currentObjective = requireText(currentObjective, "currentObjective");
            currentContent = requireText(currentContent, "currentContent");
            currentStatus = Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        }
        if (masteryScore < 0 || masteryScore > 100 || bestScore < 0 || bestScore > 100) {
            throw new IllegalArgumentException("scores must be between 0 and 100");
        }
        if (completedItemCount < 0 || totalItemCount < 0 || completedItemCount > totalItemCount) {
            throw new IllegalArgumentException("invalid progress summary");
        }
        workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    }

    /** 返回只读系统上下文；不包含 Session ID、路径、凭据或模型私有状态。 */
    public String asSystemContext() {
        var context = """
                <tutor-context>
                learner: %s
                learner-background: %s
                journey: %s
                journey-goal: %s
                session-mode: %s
                language-pack: %s
                current-learn-unit: %s
                current-title: %s
                objective: %s
                content: %s
                current-status: %s
                mastery-score: %d
                best-score: %d
                practice-verified: %s
                journey-progress: %d/%d
                workspace-kind: %s
                workspace-id: %s
                </tutor-context>
                """.formatted(
                learnerDisplayName,
                learnerBackgroundSummary,
                journeyTitle,
                journeyGoalDescription,
                mode,
                languagePackId,
                currentLearnUnitCode,
                currentLearnUnitTitle,
                currentObjective,
                currentContent,
                currentStatus,
                masteryScore,
                bestScore,
                practiceVerified,
                completedItemCount,
                totalItemCount,
                workspace.kind(),
                workspace.id());
        if (mode == TutorSessionMode.PLANNING) {
            return context + "\n当前处于路径规划模式。请围绕 Journey 目标与 Learner 背景讨论并提出可调整的学习路径；不要声称已经保存路径。";
        }
        return context;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
