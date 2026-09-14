package com.example.agent.learning.catalog;

import com.example.agent.learning.generation.GenerationOperation;
import com.example.agent.learning.generation.GenerationRunService;
import com.example.agent.learning.progress.ProgressService;
import org.springframework.stereotype.Service;

/** 基于共享 GenerationRun 生命周期的 LearnUnit 内容适配器。 */
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
        return generation.start(GenerationOperation.LEARN_UNIT_CONTENT, targetKey, run ->
                generate(run, journeyId, learnUnitCode, action));
    }

    private void generate(
            GenerationRunService.Run run, String journeyId, String learnUnitCode, EntryAction action) {
        run.emit("agent_message", "PREPARING", "Agent", "正在整理 LearnUnit 大纲和学习者背景。", null);
        run.emit("stage_changed", "CALLING_MODEL", "Agent", "正在调用大模型生成教学内容。", null);
        CurriculumGenerator.GeneratedLearnUnitContent generated =
                curriculum.generateLearnUnitContent(journeyId, learnUnitCode, run::modelText);
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
    }

    private void applyPathAction(String journeyId, String learnUnitCode, EntryAction action) {
        switch (action) {
            case CONTINUE -> progress.startLearnUnit(journeyId, learnUnitCode);
            case REVIEW -> {
                // 复习模式不改变已完成的 LearningPathItem。
            }
        }
    }

    public enum EntryAction {
        CONTINUE,
        REVIEW
    }
}
