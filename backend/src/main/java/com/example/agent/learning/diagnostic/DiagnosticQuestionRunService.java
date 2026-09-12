package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.AssessmentService;
import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Diagnostic question adapter on top of the shared GenerationRun lifecycle. */
@Service
public final class DiagnosticQuestionRunService {

    private final AssessmentService assessments;
    private final GenerationRunService generation;

    public DiagnosticQuestionRunService(AssessmentService assessments, GenerationRunService generation) {
        this.assessments = assessments;
        this.generation = generation;
    }

    public DiagnosticStart start(String journeyId) {
        if (!assessments.requiresDiagnosticGeneration(journeyId)) {
            return new DiagnosticStart(null, assessments.createDiagnostic(journeyId));
        }
        GenerationRunService.StartResult started = generation.start(
                "DIAGNOSTIC_QUESTIONS", journeyId, run -> generate(run, journeyId));
        return new DiagnosticStart(started.run().id(), null);
    }

    public Flux<GenerationEvent> events(String runId, long lastSequence) {
        return generation.events(runId, lastSequence);
    }

    public void cancel(String runId) {
        GenerationRunService.Run run = generation.run(runId);
        if (!"DIAGNOSTIC_QUESTIONS".equals(run.operation())) {
            throw new IllegalArgumentException("generation run is not diagnostic question generation: " + runId);
        }
        if ("PERSISTING".equals(run.stage())) {
            throw new IllegalStateException("Diagnostic questions are being saved");
        }
        run.cancel("本次诊断题生成已取消。");
    }

    private void generate(GenerationRunService.Run run, String journeyId) {
        try {
            run.emit("agent_message", "PREPARING", "Agent", "正在整理诊断范围、LearnUnit 归属和已有题库。", null);
            run.emit("stage_changed", "CALLING_MODEL", "Agent", "正在调用大模型规划诊断题集。", null);
            AssessmentService.AssessmentState state = assessments.createDiagnostic(
                    journeyId,
                    ignored -> run.modelActivity(),
                    progress -> {
                        if (run.terminal()) throw new IllegalStateException("diagnostic generation was cancelled");
                        DiagnosticQuestionPreview preview = DiagnosticQuestionPreview.from(progress.questions());
                        if ("VALIDATING".equals(progress.stage())) {
                            run.emit("validation", "VALIDATING", "Agent", "题目结构、题型覆盖和 LearnUnit 归属校验通过。", preview);
                        } else if ("PERSISTING".equals(progress.stage())) {
                            run.emit("persistence", "PERSISTING", "Agent", "正在原子保存诊断题集和固定 Assessment。", preview);
                        }
                    });
            if (run.terminal()) return;
            DiagnosticQuestionPreview preview = DiagnosticQuestionPreview.from(state.questions());
            run.complete("诊断题集已准备好，可以开始评估。", preview, "ASSESSMENT", state.assessment().id());
        } catch (RuntimeException error) {
            if (!run.terminal()) run.fail("诊断题生成失败，请检查模型配置后重试。");
        }
    }

    public record DiagnosticStart(String runId, AssessmentService.AssessmentState assessment) {
    }
}
