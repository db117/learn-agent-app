package com.example.agent.learning.assessment;

import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Coding 评估适配器：复用 GenerationRun，题集和业务状态仍由 AssessmentService 管理。 */
@Service
public final class CodingEvaluationRunService {

    private final AssessmentService assessments;
    private final GenerationRunService generation;

    public CodingEvaluationRunService(AssessmentService assessments, GenerationRunService generation) {
        this.assessments = assessments;
        this.generation = generation;
    }

    public Start start(String assessmentId) {
        if (!assessments.requiresCodingEvaluation(assessmentId)) {
            return new Start(null, assessments.submit(assessmentId));
        }
        GenerationRunService.StartResult started = generation.start(
                "CODING_EVALUATION", assessmentId, run -> evaluate(run, assessmentId));
        return new Start(started.run().id(), null);
    }

    public Flux<GenerationEvent> events(String runId, long lastSequence) {
        return generation.events(runId, lastSequence);
    }

    public void cancel(String runId) {
        GenerationRunService.Run run = generation.run(runId);
        if (!"CODING_EVALUATION".equals(run.operation())) {
            throw new IllegalArgumentException("generation run is not Coding evaluation: " + runId);
        }
        if ("PERSISTING".equals(run.stage())) {
            throw new IllegalStateException("Coding evaluation is being saved");
        }
        run.cancel("本次 Coding 评估已取消，答案草稿已保留。");
    }

    private void evaluate(GenerationRunService.Run run, String assessmentId) {
        try {
            run.emit("agent_message", "PREPARING", "Agent", "正在读取本次评估的固定题集和答案草稿。", null);
            run.emit("stage_changed", "CALLING_MODEL", "Agent", "正在分析 Coding 答案。", null);
            AssessmentService.AssessmentSubmission result = assessments.submit(assessmentId, stage -> {
                if (run.terminal()) throw new IllegalStateException("coding evaluation was cancelled");
                switch (stage) {
                    case "ANALYZING", "MODEL_ACTIVITY" ->
                            run.modelActivity();
                    case "VALIDATING" ->
                            run.emit("validation", "VALIDATING", "Agent", "正在校验评分维度和反馈结构。", null);
                    case "PERSISTING" ->
                            run.emit("persistence", "PERSISTING", "Agent", "正在保存评分反馈和评估结果。", null);
                    default -> {
                    }
                }
            });
            if (run.terminal()) return;
            run.complete("Coding 评估已完成，可以查看结果。", null, "ASSESSMENT_RESULT", assessmentId);
        } catch (RuntimeException error) {
            if (!run.terminal()) run.fail("Coding 评估失败，答案草稿已保留，请重试。");
        }
    }

    public record Start(String runId, AssessmentService.AssessmentSubmission submission) {
    }
}
