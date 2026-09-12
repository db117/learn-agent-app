package com.example.agent.learning.journey;

import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Journey outline adapter on top of the shared GenerationRun lifecycle. */
@Service
public final class JourneyDraftRunService {

    private final CurriculumService curriculum;
    private final LearningJourneyService journeys;
    private final GenerationRunService generation;
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

    public JourneyDraftRunService(
            CurriculumService curriculum,
            LearningJourneyService journeys,
            GenerationRunService generation) {
        this.curriculum = curriculum;
        this.journeys = journeys;
        this.generation = generation;
    }

    public String start(String userId, JourneyDraftInput input) {
        journeys.learningContext(input);
        String targetKey = userId + "|" + input.languageCode() + "|" + input.goal() + "|"
                + input.primaryLanguage() + "|" + input.experienceYears() + "|"
                + input.selfDescription() + "|" + input.learningGoal();
        synchronized (drafts) {
            Draft draft = new Draft(userId, input);
            GenerationRunService.StartResult started = generation.start(
                    "JOURNEY_OUTLINE", targetKey, run -> {
                        draft.run = run;
                        generate(draft, run);
                    });
            if (started.created()) {
                draft.run = started.run();
                drafts.put(started.run().id(), draft);
            }
            return started.run().id();
        }
    }

    public Flux<GenerationEvent> events(String runId, long lastSequence) {
        return generation.events(runId, lastSequence);
    }

    public Flux<GenerationEvent> events(String runId) {
        return events(runId, -1);
    }

    public void guide(String runId, String content) {
        Draft draft = draft(runId);
        if (content == null || content.isBlank()) throw new IllegalArgumentException("guidance must not be blank");
        synchronized (draft) {
            if (draft.run.terminal() || "PERSISTING".equals(draft.run.stage())) {
                throw new IllegalStateException("journey draft is no longer accepting guidance");
            }
            draft.guidance.add(content.trim());
            draft.run.emit("user_message", "WAITING_CONFIRMATION", "用户", content.trim(), preview(draft.outline));
            if ("WAITING_CONFIRMATION".equals(draft.run.stage())) {
                draft.run.scheduleNext(run -> generate(draft, run));
            }
        }
    }

    public void confirm(String runId) {
        Draft draft = draft(runId);
        synchronized (draft) {
            if (!"WAITING_CONFIRMATION".equals(draft.run.stage()) || draft.outline == null) {
                throw new IllegalStateException("journey draft is not ready for confirmation");
            }
            draft.run.stage("PERSISTING");
            draft.run.emit("persistence", "PERSISTING", "Agent", "大纲已确认，正在保存 Journey。", preview(draft.outline));
            draft.run.scheduleNext(run -> commit(draft, run));
        }
    }

    public void cancel(String runId) {
        Draft draft = draft(runId);
        synchronized (draft) {
            if (draft.run.terminal()) return;
            if ("PERSISTING".equals(draft.run.stage())) {
                throw new IllegalStateException("journey draft is being saved");
            }
            draft.run.cancel("本次 Journey 创建已取消。");
        }
    }

    private void generate(Draft draft, GenerationRunService.Run run) {
        List<String> guidance;
        int guidanceCount;
        CurriculumGenerator.GeneratedOutline previousOutline;
        synchronized (draft) {
            guidanceCount = draft.guidance.size();
            guidance = List.copyOf(draft.guidance);
            previousOutline = draft.outline;
        }
        try {
            run.emit("agent_message", "PREPARING", "Agent", "正在整理学习目标和已有调整要求。", preview(previousOutline));
            String context = journeys.learningContext(draft.input)
                    + outlineContext(previousOutline) + guidanceContext(guidance);
            run.emit("stage_changed", "CALLING_MODEL", "Agent", "正在调用大模型生成 Journey 大纲。", null);
            CurriculumGenerator.GeneratedOutline generated = curriculum.generateOutlineForJourney(
                    run.id(), draft.input.languageCode(), context, ignored -> run.modelActivity());
            synchronized (draft) {
                if (run.terminal()) return;
                run.stage("VALIDATING");
                generated = preserveConfirmedRules(previousOutline, generated);
                draft.outline = generated;
                run.emit("validation", "VALIDATING", "Agent", "大纲结构校验通过，正在准备安全预览。", preview(generated));
                run.stage("WAITING_CONFIRMATION");
                run.emit("draft_ready", "WAITING_CONFIRMATION", "大模型", "大纲已生成，请确认知识点和学习路径。", preview(generated));
                if (draft.guidance.size() > guidanceCount) {
                    run.scheduleNext(next -> generate(draft, next));
                }
            }
        } catch (RuntimeException error) {
            if (!run.terminal()) run.fail("大纲生成失败，请检查目标或模型配置后重试。");
        }
    }

    private void commit(Draft draft, GenerationRunService.Run run) {
        try {
            journeys.confirmOutline(draft.userId, run.id(), draft.input, draft.outline);
            run.complete("Journey 已保存，可以开始学习第一个单元。", preview(draft.outline), "JOURNEY", run.id());
        } catch (RuntimeException error) {
            if (!run.terminal()) run.fail("Journey 保存失败，请稍后重试。");
        }
    }

    private Draft draft(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        Draft draft = drafts.get(runId);
        if (draft == null) throw new IllegalArgumentException("journey draft not found: " + runId);
        return draft;
    }

    private JourneyOutlinePreview preview(CurriculumGenerator.GeneratedOutline outline) {
        return outline == null ? null : JourneyOutlinePreview.from(outline);
    }

    private String guidanceContext(List<String> guidance) {
        if (guidance.isEmpty()) return "";
        return "\n用户在对话中追加的调整要求：\n- " + String.join("\n- ", guidance);
    }

    private String outlineContext(CurriculumGenerator.GeneratedOutline outline) {
        if (outline == null) return "";
        String chapters = outline.chapters().stream()
                .map(chapter -> "- Chapter %d. %s (%s)：%s；前置=%s"
                        .formatted(chapter.sequence(), chapter.name(), chapter.code(), chapter.goal(),
                                chapter.prerequisiteChapterCodes()))
                .reduce("\n", (left, right) -> left + right + "\n");
        String units = outline.learnUnits().stream()
                .map(unit -> "- %d. %s (%s, Chapter=%s)：%s；前置=%s；目标=%s；知识点=%s"
                        .formatted(unit.sequence(), unit.name(), unit.code(), unit.chapterCode(), unit.description(),
                                unit.prerequisiteLearnUnitCodes(), unit.learningObjectives(), unit.keyConcepts()))
                .reduce("\n", (left, right) -> left + right + "\n");
        return "\n当前待确认的大纲如下；后续调整只能改 Chapter、知识点、单元说明和学习顺序/前置关系，不能改评分规则："
                + chapters + units;
    }

    private CurriculumGenerator.GeneratedOutline preserveConfirmedRules(
            CurriculumGenerator.GeneratedOutline previous,
            CurriculumGenerator.GeneratedOutline generated) {
        if (previous == null) return generated;
        Map<String, LearnUnit> previousUnits = previous.learnUnits().stream()
                .collect(java.util.stream.Collectors.toMap(LearnUnit::code, unit -> unit));
        List<LearnUnit> units = generated.learnUnits().stream()
                .map(unit -> {
                    LearnUnit old = previousUnits.get(unit.code());
                    if (old == null) return unit;
                    return new LearnUnit(
                            unit.id(), unit.languageCode(), unit.code(), unit.chapterCode(), unit.name(), unit.description(),
                            unit.sequence(), unit.prerequisiteLearnUnitCodes(), old.passScore(), old.minCodingScore(), old.enabled(),
                            unit.learningObjectives(), unit.lessonIntro(), unit.keyConcepts(), unit.examples(),
                            old.diagnosticEligible());
                })
                .toList();
        return new CurriculumGenerator.GeneratedOutline(previous.languages(), generated.chapters(), units);
    }

    private final class Draft {
        private final String userId;
        private final JourneyDraftInput input;
        private GenerationRunService.Run run;
        private final List<String> guidance = new ArrayList<>();
        private CurriculumGenerator.GeneratedOutline outline;

        private Draft(String userId, JourneyDraftInput input) {
            this.userId = userId;
            this.input = input;
        }
    }
}
