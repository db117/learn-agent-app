package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.domain.*;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Tutor 对当前代码提交的评估工具；只追加评估候选，不写入学习进度。 */
@Dependent
public final class TutorProgressTools {
    private static final String CODE_TASK_TYPE = "CODE";

    private final JourneyApplicationService journeys;
    private final PracticeTaskRepository practiceTasks;
    private final PracticeAssessmentRepository assessments;
    private final WorkspaceManager workspaces;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public TutorProgressTools(
            JourneyApplicationService journeys,
            PracticeTaskRepository practiceTasks,
            PracticeAssessmentRepository assessments,
            WorkspaceManager workspaces) {
        this.journeys = Objects.requireNonNull(journeys, "journeys must not be null");
        this.practiceTasks = Objects.requireNonNull(practiceTasks, "practiceTasks must not be null");
        this.assessments = Objects.requireNonNull(assessments, "assessments must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
    }

    @Tool(
            name = "record_practice_assessment",
            description = "为最新的已保存编码提交保存 Tutor 的 READY 或 CONTINUE 评估候选；必须提供学习者可见的简要依据，不能推进学习进度。")
    public String recordPracticeAssessment(
            TutorContext context,
            @ToolParam(name = "attempt_id", description = "App 提交检查返回的 assessmentAttemptId") long attemptId,
            @ToolParam(name = "verdict", description = "READY 或 CONTINUE") String verdict,
            @ToolParam(name = "rationale", description = "面向学习者的简要判断依据；可说明对客观检查的豁免理由") String rationale) {
        LearningJourney journey = currentJourney(context);
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = journey.currentItem();
        long learnUnitId = Objects.requireNonNull(
                journey.learnUnit(currentItem.learnUnitCode()).id(), "persisted LearnUnit id must not be null");
        PracticeSubmission latest = practiceTasks.findByLearnUnit(journey.id(), learnUnitId).stream()
                .filter(task -> CODE_TASK_TYPE.equals(task.type()))
                .flatMap(task -> task.attempts().stream().map(attempt -> new PracticeSubmission(task, attempt)))
                .max(Comparator.comparing((PracticeSubmission value) -> value.attempt().submittedAt())
                        .thenComparing(value -> value.attempt().id() == null ? 0L : value.attempt().id()))
                .orElseThrow(() -> LearningRequestException.conflict(
                        "PRACTICE_CHECK_REQUIRED", "请先在 App 提交编码检查"));
        if (latest.attempt().id() == null || latest.attempt().id() != attemptId) {
            throw LearningRequestException.conflict(
                    "PRACTICE_ATTEMPT_OUTDATED", "当前提交已变化，请重新检查后再评估");
        }
        String currentDigest = contentDigest(context);
        String checkedDigest = latest.attempt().evidence().workspaceDigest();
        if (checkedDigest.isBlank() || !checkedDigest.equals(currentDigest)) {
            throw LearningRequestException.conflict(
                    "PRACTICE_CHECK_STALE", "代码在检查后发生变化，请重新提交检查");
        }

        PracticeAssessmentVerdict parsedVerdict;
        try {
            parsedVerdict = PracticeAssessmentVerdict.valueOf(
                    verdict == null ? "" : verdict.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw LearningRequestException.badRequest("INVALID_ASSESSMENT", "评估结论必须是 READY 或 CONTINUE");
        }
        PracticeAssessment assessment = assessments.save(PracticeAssessment.create(
                journey.id(),
                learnUnitId,
                Objects.requireNonNull(latest.task().id(), "persisted PracticeTask id must not be null"),
                attemptId,
                parsedVerdict,
                rationale,
                checkedDigest,
                Instant.now()));
        return json(assessment);
    }

    private String contentDigest(TutorContext context) {
        try {
            return workspaces.contentDigest(workspaces.learningWorkspace(context.journeyId()));
        } catch (IOException error) {
            throw new IllegalStateException("无法读取当前学习目录以确认提交是否变化", error);
        }
    }

    private LearningJourney currentJourney(TutorContext context) {
        if (context == null || context.mode() != TutorSessionMode.LEARNING) {
            throw new IllegalStateException("进度评估工具只在 LEARNING Session 中可用");
        }
        LearningJourney journey = journeys.learningJourneyFor(context.journeyId());
        com.db117.learnagent.learning.domain.LearningPathItem current = journey.currentItem();
        if (current == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        if (!current.learnUnitCode().equals(context.currentLearnUnitCode())) {
            throw LearningRequestException.conflict("LEARN_UNIT_CHANGED", "当前 LearnUnit 已变化，请重新开始评估");
        }
        return journey;
    }

    private String json(PracticeAssessment assessment) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("assessmentId", assessment.id());
        result.put("attemptId", assessment.practiceAttemptId());
        result.put("verdict", assessment.verdict().name());
        result.put("rationale", assessment.rationale());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化 Tutor 评估结果", error);
        }
    }

    /**
     * 当前最新提交及其不可变证据。
     *
     * @param task 提交所属的编码 PracticeTask
     * @param attempt Tutor 本次将要评估的已保存尝试
     */
    private record PracticeSubmission(
            PracticeTask task,
            PracticeAttempt attempt) {
    }
}
