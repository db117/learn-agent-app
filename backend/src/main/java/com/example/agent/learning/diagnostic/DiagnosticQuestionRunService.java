package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.AssessmentService;
import com.example.agent.learning.generation.GenerationOperation;
import com.example.agent.learning.generation.GenerationRunService;
import org.springframework.stereotype.Service;

/** 基于共享 GenerationRun 生命周期的诊断题适配器。 */
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
                GenerationOperation.DIAGNOSTIC_QUESTIONS, journeyId, run -> generate(run, journeyId));
        return new DiagnosticStart(started.runId(), null);
    }

    private void generate(GenerationRunService.Run run, String journeyId) {
        run.emit("agent_message", "PREPARING", "Agent", "正在整理诊断范围、LearnUnit 归属和已有题库。", null);
        run.emit("stage_changed", "CALLING_MODEL", "Agent", "正在调用大模型规划诊断题集。", null);
        AssessmentService.AssessmentState state = assessments.createDiagnostic(
                journeyId,
                run::modelText,
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
    }

    public record DiagnosticStart(String runId, AssessmentService.AssessmentState assessment) {
    }
}
