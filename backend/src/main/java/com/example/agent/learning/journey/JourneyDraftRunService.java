package com.example.agent.learning.journey;

import com.example.agent.config.AppProperties;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.catalog.LearnUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * 管理首次 Journey 大纲生成的短生命周期运行。
 *
 * <p>模型调用和 SQLite 提交都在工作线程执行；运行事件不进入 SQLite，只进入 SSE 和本地 JSONL。</p>
 */
@Service
public final class JourneyDraftRunService {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|bearer)(?:\\s*\\\"?\\s*[:=]\\s*\\\"?|\\s+)[^\\s,}\\\"]+(?:\\s+[^\\s,}\\\"]+)?");

    private final CurriculumService curriculum;
    private final LearningJourneyService journeys;
    private final AppProperties properties;
    private final ExecutorService executor;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public JourneyDraftRunService(
            CurriculumService curriculum,
            LearningJourneyService journeys,
            AppProperties properties,
            ExecutorService executor) {
        this.curriculum = curriculum;
        this.journeys = journeys;
        this.properties = properties;
        this.executor = executor;
    }

    public String start(String userId, JourneyDraftInput input) {
        journeys.learningContext(input);
        String runId = UUID.randomUUID().toString();
        Run run = new Run(runId, userId, input);
        runs.put(runId, run);
        run.emit("run_started", "Agent", "已收到你的学习目标，先生成知识点和学习路径大纲。", null, "GENERATING");
        run.emit("user_message", "用户", userMessage(input), null, "GENERATING");
        scheduleGeneration(run);
        return runId;
    }

    public Flux<JourneyDraftEvent> events(String runId) {
        return run(runId).events.asFlux();
    }

    public void guide(String runId, String content) {
        Run run = run(runId);
        if (content == null || content.isBlank()) throw new IllegalArgumentException("guidance must not be blank");
        synchronized (run) {
            if (run.terminal() || run.status == RunStatus.CONFIRMING) {
                throw new IllegalStateException("journey draft is no longer accepting guidance");
            }
            run.guidance.add(content.trim());
            run.emit("user_message", "用户", content.trim(), run.outline, run.status.name());
            if (run.status == RunStatus.WAITING_CONFIRMATION) {
                scheduleGeneration(run);
            }
        }
    }

    public void confirm(String runId) {
        Run run = run(runId);
        synchronized (run) {
            if (run.status != RunStatus.WAITING_CONFIRMATION || run.outline == null) {
                throw new IllegalStateException("journey draft is not ready for confirmation");
            }
            run.status = RunStatus.CONFIRMING;
            run.emit("agent_message", "Agent", "知识点和学习路径已确认，正在保存 Journey。", run.outline, run.status.name());
            run.activeTask = executor.submit(() -> commit(run));
        }
    }

    public void cancel(String runId) {
        Run run = run(runId);
        synchronized (run) {
            if (run.terminal()) return;
            if (run.status == RunStatus.CONFIRMING) {
                throw new IllegalStateException("journey draft is being saved");
            }
            run.status = RunStatus.CANCELLED;
            if (run.activeTask != null) run.activeTask.cancel(true);
            run.emit("cancelled", "系统", "本次 Journey 创建已取消。", null, run.status.name());
            run.events.tryEmitComplete();
        }
    }

    private void scheduleGeneration(Run run) {
        synchronized (run) {
            if (run.terminal() || run.status == RunStatus.GENERATING || run.status == RunStatus.CONFIRMING) return;
            run.status = RunStatus.GENERATING;
            run.activeTask = executor.submit(() -> generate(run));
        }
    }

    private void generate(Run run) {
        List<String> guidance;
        int guidanceCount;
        CurriculumGenerator.GeneratedOutline previousOutline;
        synchronized (run) {
            guidanceCount = run.guidance.size();
            guidance = List.copyOf(run.guidance);
            previousOutline = run.outline;
        }
        try {
            run.emit("agent_message", "Agent", "正在把你的目标整理成下一轮模型对话。", run.outline, run.status.name());
            String context = journeys.learningContext(run.input)
                    + outlineContext(previousOutline) + guidanceContext(guidance);
            CurriculumGenerator.GeneratedOutline generated = curriculum.generateOutlineForJourney(
                    run.id, run.input.languageCode(), context,
                    text -> run.emit("model_delta", "大模型", text, null, run.status.name()));
            boolean rerun;
            synchronized (run) {
                if (run.status == RunStatus.CANCELLED) return;
                generated = preserveConfirmedRules(previousOutline, generated);
                run.outline = generated;
                run.status = RunStatus.WAITING_CONFIRMATION;
                run.emit("outline_ready", "大模型", "大纲已生成，请确认要学习的知识点和路径。", generated, run.status.name());
                rerun = run.guidance.size() > guidanceCount;
            }
            if (rerun) scheduleGeneration(run);
        } catch (RuntimeException error) {
            synchronized (run) {
                if (run.status == RunStatus.CANCELLED) return;
                run.outline = null;
                run.status = RunStatus.FAILED;
                run.emit("error", "系统", "大纲生成失败：" + safeMessage(error), null, run.status.name());
                run.events.tryEmitComplete();
            }
        }
    }

    private void commit(Run run) {
        try {
            journeys.confirmOutline(run.userId, run.id, run.input, run.outline);
            synchronized (run) {
                if (run.status == RunStatus.CANCELLED) return;
                run.status = RunStatus.CONFIRMED;
                run.emit("confirmed", "系统", "Journey 已保存，可以开始学习第一个单元。", run.outline, run.status.name());
                run.events.tryEmitComplete();
            }
        } catch (RuntimeException error) {
            synchronized (run) {
                if (run.status == RunStatus.CANCELLED) return;
                run.outline = null;
                run.status = RunStatus.FAILED;
                run.emit("error", "系统", "Journey 保存失败：" + safeMessage(error), null, run.status.name());
                run.events.tryEmitComplete();
            }
        }
    }

    private Run run(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        Run run = runs.get(runId);
        if (run == null) throw new IllegalArgumentException("journey draft not found: " + runId);
        return run;
    }

    private String userMessage(JourneyDraftInput input) {
        return "目标语言：" + input.languageCode() + "；Journey：" + input.goal()
                + "；学习目标：" + input.learningGoal();
    }

    private String guidanceContext(List<String> guidance) {
        if (guidance.isEmpty()) return "";
        return "\n用户在对话中追加的调整要求：\n- " + String.join("\n- ", guidance);
    }

    private String outlineContext(CurriculumGenerator.GeneratedOutline outline) {
        if (outline == null) return "";
        String units = outline.learnUnits().stream()
                .map(unit -> "- %d. %s (%s)：%s；前置=%s；目标=%s；知识点=%s"
                        .formatted(unit.sequence(), unit.name(), unit.code(), unit.description(),
                                unit.prerequisiteLearnUnitCodes(), unit.learningObjectives(), unit.keyConcepts()))
                .reduce("\n", (left, right) -> left + right + "\n");
        return "\n当前待确认的大纲如下；后续调整只能改知识点、单元说明和学习顺序/前置关系，不能改评分规则：" + units;
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
                            unit.id(), unit.languageCode(), unit.code(), unit.name(), unit.description(), unit.sequence(),
                            unit.prerequisiteLearnUnitCodes(), old.passScore(), old.minCodingScore(), old.enabled(),
                            unit.learningObjectives(), unit.lessonIntro(), unit.keyConcepts(), unit.examples(),
                            old.diagnosticEligible());
                })
                .toList();
        return new CurriculumGenerator.GeneratedOutline(previous.languages(), units);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return SECRET.matcher(message == null || message.isBlank() ? error.getClass().getSimpleName() : message)
                .replaceAll("$1=[REDACTED]");
    }

    private enum RunStatus {
        GENERATING, WAITING_CONFIRMATION, CONFIRMING, CONFIRMED, FAILED, CANCELLED
    }

    private final class Run {
        private final String id;
        private final String userId;
        private final JourneyDraftInput input;
        private final Sinks.Many<JourneyDraftEvent> events = Sinks.many().replay().all();
        private final List<String> guidance = new ArrayList<>();
        private volatile RunStatus status = RunStatus.WAITING_CONFIRMATION;
        private CurriculumGenerator.GeneratedOutline outline;
        private long sequence;
        private Future<?> activeTask;

        private Run(String id, String userId, JourneyDraftInput input) {
            this.id = id;
            this.userId = userId;
            this.input = input;
        }

        private boolean terminal() {
            return status == RunStatus.CONFIRMED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
        }

        private synchronized void emit(
                String eventType, String author, String content,
                CurriculumGenerator.GeneratedOutline eventOutline, String eventStatus) {
            JourneyDraftEvent event = new JourneyDraftEvent(
                    ++sequence, id, author, eventType, content, eventOutline,
                    eventType.equals("confirmed") ? id : null, eventStatus, Instant.now());
            events.tryEmitNext(event);
            log(event);
        }
    }

    private void log(JourneyDraftEvent event) {
        executor.submit(() -> {
            try {
                Path directory = Path.of(properties.dataDir(), "journey-runs");
                Files.createDirectories(directory);
                String content = SECRET.matcher(event.content() == null ? "" : event.content())
                        .replaceAll("$1=[REDACTED]");
                JourneyDraftEvent safe = new JourneyDraftEvent(
                        event.sequence(), event.runId(), event.author(), event.eventType(), content,
                        event.outline(), event.journeyId(), event.status(), event.timestamp());
                String serialized = SECRET.matcher(mapper.writeValueAsString(safe))
                        .replaceAll("$1=[REDACTED]");
                Files.writeString(
                        directory.resolve(event.runId() + ".jsonl"), serialized + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException error) {
                // Local diagnostics must never turn a successful learning run into a failed run.
            }
        });
    }
}
