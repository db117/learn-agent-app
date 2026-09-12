package com.example.agent.learning.catalog;

import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import com.example.agent.learning.progress.ProgressService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** LearnUnit content adapter on top of the shared GenerationRun lifecycle. */
@Service
public final class LearnUnitContentRunService {

    private final CurriculumService curriculum;
    private final ProgressService progress;
    private final GenerationRunService generation;

    public LearnUnitContentRunService(
            CurriculumService curriculum, ProgressService progress, GenerationRunService generation) {
        this.curriculum = curriculum;
        this.progress = progress;
        this.generation = generation;
    }

    public GenerationRunService.StartResult start(
            String journeyId, String learnUnitCode, EntryAction action) {
        curriculum.learnUnitOutline(journeyId, learnUnitCode);
        String targetKey = journeyId + "|" + learnUnitCode;
        return generation.start("LEARN_UNIT_CONTENT", targetKey, run ->
                generate(run, journeyId, learnUnitCode, action));
    }

    public Flux<GenerationEvent> events(String runId, long lastSequence) {
        return generation.events(runId, lastSequence);
    }

    public void cancel(String runId) {
        GenerationRunService.Run run = generation.run(runId);
        if (!"LEARN_UNIT_CONTENT".equals(run.operation())) {
            throw new IllegalArgumentException("generation run is not LearnUnit content: " + runId);
        }
        if ("PERSISTING".equals(run.stage())) {
            throw new IllegalStateException("LearnUnit content is being saved");
        }
        run.cancel("本次 LearnUnit 内容生成已取消。");
    }

    private void generate(
            GenerationRunService.Run run, String journeyId, String learnUnitCode, EntryAction action) {
        try {
            run.emit("agent_message", "PREPARING", "Agent", "正在整理 LearnUnit 大纲和学习者背景。", null);
            run.emit("stage_changed", "CALLING_MODEL", "Agent", "正在调用大模型生成教学内容。", null);
            CurriculumGenerator.GeneratedLearnUnitContent generated =
                    curriculum.generateLearnUnitContent(journeyId, learnUnitCode, ignored -> run.modelActivity());
            if (run.terminal()) return;

            LearnUnitContentPreview preview = LearnUnitContentPreview.from(
                    generated.learnUnit(), generated.independentQuestions().size());
            run.emit("validation", "VALIDATING", "Agent", "教学内容和独立检查题结构校验通过。", preview);
            if (run.terminal()) return;

            run.emit("persistence", "PERSISTING", "Agent", "正在原子保存教学内容和独立检查题。", preview);
            curriculum.persistLearnUnitContent(journeyId, learnUnitCode, generated);
            if (run.terminal()) return;
            applyPathAction(journeyId, learnUnitCode, action);
            run.complete("LearnUnit 内容已准备好，可以开始学习。", preview, "LEARN_UNIT", learnUnitCode);
        } catch (RuntimeException error) {
            if (!run.terminal()) run.fail("LearnUnit 内容生成失败，请重试。");
        }
    }

    private void applyPathAction(String journeyId, String learnUnitCode, EntryAction action) {
        switch (action) {
            case START, CONTINUE -> progress.startLearnUnit(journeyId, learnUnitCode);
            case REVIEW -> {
                // Review does not change the completed LearningPathItem.
            }
        }
    }

    public enum EntryAction {
        START,
        CONTINUE,
        REVIEW
    }
}
