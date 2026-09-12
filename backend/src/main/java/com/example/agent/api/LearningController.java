package com.example.agent.api;

import com.example.agent.config.AppProperties;
import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentService;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAnswer;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.catalog.LearnUnitContentRunService;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.JourneyDraftInput;
import com.example.agent.learning.journey.JourneyDraftRunService;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.example.agent.learning.generation.GenerationEvent;
import com.example.agent.learning.generation.GenerationRunService;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPhase;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.tutor.TutorSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Learning Journey HTTP 入口。
 *
 * <p>Controller 只负责参数校验、响应组装和 HTTP 状态码；评分、题集固定、
 * Path 推进等跨表业务由 Learning Service 负责。</p>
 */
@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LearningRepository learning;
    private final CurriculumService curriculum;
    private final LearningJourneyService journeys;
    private final ProgressService progress;
    private final AssessmentService assessments;
    private final TutorSessionService tutorSessions;
    private final JourneyDraftRunService journeyDrafts;
    private final LearnUnitContentRunService learnUnitContentRuns;
    private final GenerationRunService generation;
    private final AppProperties properties;

    public LearningController(
            LearningRepository learning,
            CurriculumService curriculum,
            LearningJourneyService journeys,
            ProgressService progress,
            AssessmentService assessments,
            TutorSessionService tutorSessions,
            JourneyDraftRunService journeyDrafts,
            LearnUnitContentRunService learnUnitContentRuns,
            GenerationRunService generation,
            AppProperties properties) {
        this.learning = learning;
        this.curriculum = curriculum;
        this.journeys = journeys;
        this.progress = progress;
        this.assessments = assessments;
        this.tutorSessions = tutorSessions;
        this.journeyDrafts = journeyDrafts;
        this.learnUnitContentRuns = learnUnitContentRuns;
        this.generation = generation;
        this.properties = properties;
    }

    /** 查询已经写入 SQLite 的学习语言，不会为读取接口自动生成新语言。 */
    @GetMapping("/languages")
    public List<LearningLanguage> languages() {
        return curriculum.listLanguages();
    }

    /** 查询当前 Journey 专属的 LearnUnit 目录。 */
    @GetMapping("/journeys/{id}/learn-units")
    public List<LearnUnit> journeyLearnUnits(@PathVariable String id) {
        journeys.get(id);
        return learning.listLearnUnitsForJourney(id);
    }

    /** 启动首次 Journey 大纲生成；真正写库由确认接口触发。 */
    @PostMapping("/journey-drafts")
    public JourneyDraftStartResponse startJourneyDraft(@RequestBody CreateJourneyRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        String runId = journeyDrafts.start(properties.userId(), new JourneyDraftInput(
                request.languageCode(), request.goal(), request.primaryLanguage(), request.experienceYears(),
                request.selfDescription(), request.learningGoal()));
        return new JourneyDraftStartResponse(runId);
    }

    /** 订阅首次 Journey 的 Agent/模型对话和大纲事件。 */
    @GetMapping(value = "/journey-drafts/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<org.springframework.http.codec.ServerSentEvent<GenerationEvent>> journeyDraftEvents(
            @PathVariable String runId,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Last-Event-ID", required = false)
            String lastEventId) {
        return journeyDrafts.events(runId, parseLastEventId(lastEventId))
                .map(event -> org.springframework.http.codec.ServerSentEvent.<GenerationEvent>builder(event)
                        .id(Long.toString(event.sequence())).build());
    }

    /** 订阅任意学习生成运行的安全 Agent/模型事件。 */
    @GetMapping(value = "/generation-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<org.springframework.http.codec.ServerSentEvent<GenerationEvent>> generationEvents(
            @PathVariable String runId,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Last-Event-ID", required = false)
            String lastEventId) {
        return generation.events(runId, parseLastEventId(lastEventId))
                .map(event -> org.springframework.http.codec.ServerSentEvent.<GenerationEvent>builder(event)
                        .id(Long.toString(event.sequence())).build());
    }

    /** 显式取消生成；SSE 断开不会触发取消。 */
    @PostMapping("/generation-runs/{runId}/cancel")
    public JourneyDraftAck cancelGenerationRun(@PathVariable String runId) {
        GenerationRunService.Run run = generation.run(runId);
        if ("JOURNEY_OUTLINE".equals(run.operation())) {
            journeyDrafts.cancel(runId);
        } else if ("LEARN_UNIT_CONTENT".equals(run.operation())) {
            learnUnitContentRuns.cancel(runId);
        } else {
            run.cancel("本次生成已取消。");
        }
        return new JourneyDraftAck("cancelled");
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return -1L;
        try {
            long sequence = Long.parseLong(value);
            if (sequence < 0) throw new NumberFormatException();
            return sequence;
        } catch (NumberFormatException error) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Last-Event-ID must be a non-negative integer");
        }
    }

    /** 把用户的调整要求排入下一轮模型对话。 */
    @PostMapping("/journey-drafts/{runId}/guidance")
    public JourneyDraftAck guideJourneyDraft(
            @PathVariable String runId, @RequestBody JourneyDraftGuidanceRequest request) {
        if (request == null) throw new IllegalArgumentException("guidance is required");
        journeyDrafts.guide(runId, request.content());
        return new JourneyDraftAck("accepted");
    }

    /** 只有用户确认知识点和路径后才提交 Journey 大纲。 */
    @PostMapping("/journey-drafts/{runId}/confirm")
    public JourneyDraftAck confirmJourneyDraft(@PathVariable String runId) {
        journeyDrafts.confirm(runId);
        return new JourneyDraftAck("accepted");
    }

    /** 取消未确认的大纲生成，不写入学习数据。 */
    @PostMapping("/journey-drafts/{runId}/cancel")
    public JourneyDraftAck cancelJourneyDraft(@PathVariable String runId) {
        journeyDrafts.cancel(runId);
        return new JourneyDraftAck("cancelled");
    }

    /** 查询当前本地用户的 Journey 列表。 */
    @GetMapping("/journeys")
    public List<LearningJourney> journeys() {
        return journeys.list(properties.userId());
    }

    /** 查询 Journey、画像、完整 Path 和 LearnUnit 状态。 */
    @GetMapping("/journeys/{id}")
    public JourneyDetailResponse journey(@PathVariable String id) {
        LearningJourney journey = journeys.get(id);
        List<LearningPathItem> path = learning.listPath(id);
        List<LearnUnit> learnUnits = learning.listLearnUnitsForJourney(id);
        List<ChapterDetail> chapters = learning.listChaptersForJourney(id).stream()
                .map(chapter -> {
                    List<LearnUnit> chapterUnits = learnUnits.stream()
                            .filter(unit -> unit.chapterCode().equals(chapter.code()))
                            .toList();
                    List<LearningPathItem> chapterPath = path.stream()
                            .filter(item -> chapterUnits.stream()
                                    .anyMatch(unit -> unit.code().equals(item.learnUnitCode())))
                            .toList();
                    int unresolvedCount = (int) chapterPath.stream()
                            .filter(item -> item.status() != com.example.agent.learning.path.LearningPathItemStatus.COMPLETED
                                    || item.needsReview())
                            .count();
                    var synthesis = learning.findLatestChapterSynthesisAssessment(id, chapter.code());
                    return new ChapterDetail(
                            chapter, chapterUnits, chapterPath,
                            (int) chapterPath.stream().filter(item -> item.status()
                                    == com.example.agent.learning.path.LearningPathItemStatus.COMPLETED).count(),
                            (int) chapterPath.stream().filter(item -> item.status()
                                    == com.example.agent.learning.path.LearningPathItemStatus.SKIPPED).count(),
                            unresolvedCount, progress.isChapterSynthesisEligible(id, chapter.code()),
                            learning.hasPassedChapterSynthesis(id, chapter.code()),
                            synthesis.map(Assessment::id).orElse(null));
                })
                .toList();
        return new JourneyDetailResponse(
                journey, learning.findProfile(id).orElse(null), chapters, path);
    }

    /** 查询 Journey 的完整学习路径，包括历史节点。 */
    @GetMapping("/journeys/{id}/path")
    public List<LearningPathItem> path(@PathVariable String id) {
        journeys.get(id);
        return learning.listPath(id);
    }

    /** 查询当前唯一可操作的 LearnUnit 节点。 */
    @GetMapping("/journeys/{id}/current")
    public LearnUnitResponse current(@PathVariable String id) {
        journeys.get(id);
        String learnUnitCode = learning.listPath(id).stream()
                .filter(item -> item.status() == com.example.agent.learning.path.LearningPathItemStatus.CURRENT)
                .map(LearningPathItem::learnUnitCode)
                .findFirst()
                .orElse(null);
        if (learnUnitCode == null) throw new IllegalStateException("journey has no current LearnUnit");
        return learnUnit(id, learnUnitCode);
    }

    /** 归档 Journey，使其不再作为进行中的学习旅程。 */
    @PostMapping("/journeys/{id}/archive")
    public LearningJourney archive(@PathVariable String id) {
        return journeys.archive(id);
    }

    /** 查询评估及其进行中/历史答案，用于页面恢复。 */
    @GetMapping("/assessments/{assessmentId}")
    public AssessmentResponse assessment(@PathVariable String assessmentId) {
        return AssessmentResponse.from(assessments.state(
                learning.findAssessment(assessmentId).orElseThrow(() -> new IllegalArgumentException("assessment not found"))));
    }

    /** 开始评估并创建一个可重试的 Attempt。 */
    @PostMapping("/assessments/{assessmentId}/start")
    public AssessmentResponse startAssessment(@PathVariable String assessmentId) {
        return AssessmentResponse.from(assessments.start(assessmentId));
    }

    /** 保存单题答案草稿；不在此接口计算最终成绩。 */
    @PostMapping("/assessments/{assessmentId}/answers")
    public AssessmentResponse answer(
            @PathVariable String assessmentId, @RequestBody AnswerRequest request) {
        if (request == null || request.questionId() == null || request.questionId().isBlank()) {
            throw new IllegalArgumentException("questionId is required");
        }
        return AssessmentResponse.from(assessments.answer(
                assessmentId, new QuestionAnswer(request.questionId(), request.selectedOptionIds(), request.submittedCode())));
    }

    /** 提交评估，计算分数并更新 LearnUnit 进度。 */
    @PostMapping("/assessments/{assessmentId}/submit")
    public AssessmentResultResponse submit(@PathVariable String assessmentId) {
        return AssessmentResultResponse.from(assessments.submit(assessmentId));
    }

    /** 将当前 Path 节点置为学习中。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/start")
    public Mono<Object> startLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return openLearnUnit(journeyId, learnUnitCode, LearnUnitContentRunService.EntryAction.START);
    }

    /** Continue the server-selected current LearnUnit after a restart or result screen. */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/continue")
    public Mono<Object> continueLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return openLearnUnit(journeyId, learnUnitCode, LearnUnitContentRunService.EntryAction.CONTINUE);
    }

    /** Review a completed LearnUnit without changing its historical path state. */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/review")
    public Mono<Object> reviewLearnUnit(
            @PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return openLearnUnit(journeyId, learnUnitCode, LearnUnitContentRunService.EntryAction.REVIEW);
    }

    private Mono<Object> openLearnUnit(
            String journeyId, String learnUnitCode, LearnUnitContentRunService.EntryAction action) {
        return Mono.<Object>fromCallable(() -> {
                    LearnUnit outline = curriculum.learnUnitOutline(journeyId, learnUnitCode);
                    validateLearnUnitEntry(journeyId, learnUnitCode, action);
                    if (outline.hasDetailedContent()) {
                        return openCachedLearnUnit(journeyId, learnUnitCode, action);
                    }
                    return new JourneyDraftStartResponse(
                            learnUnitContentRuns.start(journeyId, learnUnitCode, action).run().id());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void validateLearnUnitEntry(
            String journeyId, String learnUnitCode, LearnUnitContentRunService.EntryAction action) {
        if (action == LearnUnitContentRunService.EntryAction.REVIEW) {
            progress.requireCompletedLearnUnit(journeyId, learnUnitCode);
        } else {
            progress.requireCurrentLearnUnit(journeyId, learnUnitCode);
        }
    }

    private LearnUnitResponse openCachedLearnUnit(
            String journeyId, String learnUnitCode, LearnUnitContentRunService.EntryAction action) {
        curriculum.ensureLearnUnitContent(journeyId, learnUnitCode);
        if (action != LearnUnitContentRunService.EntryAction.REVIEW) {
            progress.startLearnUnit(journeyId, learnUnitCode);
        }
        return learnUnit(journeyId, learnUnitCode);
    }

    /** 推进当前 LearnUnit 的一个教学阶段。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/phase/{phase}/advance")
    public Mono<LearnUnitResponse> advancePhase(
            @PathVariable String journeyId,
            @PathVariable String learnUnitCode,
            @PathVariable String phase) {
        return Mono.fromCallable(() -> {
                    progress.advancePhase(journeyId, learnUnitCode, parsePhase(phase));
                    return learnUnit(journeyId, learnUnitCode);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 跳过当前教学阶段但不跳过整个 LearnUnit。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/phase/{phase}/skip")
    public Mono<LearnUnitResponse> skipPhase(
            @PathVariable String journeyId,
            @PathVariable String learnUnitCode,
            @PathVariable String phase) {
        return Mono.fromCallable(() -> {
                    progress.skipPhase(journeyId, learnUnitCode, parsePhase(phase));
                    return learnUnit(journeyId, learnUnitCode);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 保存不计分的引导练习回答和反馈。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/guided-practice")
    public Mono<LearnUnitResponse> guidedPractice(
            @PathVariable String journeyId,
            @PathVariable String learnUnitCode,
            @RequestBody GuidedPracticeRequest request) {
        if (request == null) throw new IllegalArgumentException("guided practice response is required");
        return Mono.fromCallable(() -> {
                    progress.recordGuidedPractice(journeyId, learnUnitCode, request.response());
                    return learnUnit(journeyId, learnUnitCode);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 创建或恢复指定 LearnUnit 的固定题集评估。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/assessment")
    public Mono<AssessmentResponse> learnUnitAssessment(
                @PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return Mono.fromCallable(() -> {
                    var current = progress.requireCurrentLearnUnit(journeyId, learnUnitCode);
                    if (current.startedAt() == null) {
                        throw new IllegalStateException("start learning before generating assessment questions");
                    }
                    if (current.learningPhase() != LearningPhase.INDEPENDENT_CHECK) {
                        throw new IllegalStateException("advance to the independent check before starting assessment");
                    }
                    return AssessmentResponse.from(assessments.createLearnUnitAssessment(journeyId, learnUnitCode));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 显式 practice 已完成 LearnUnit；不会改变其 Path 状态。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/practice")
    public Mono<AssessmentResponse> practiceLearnUnit(
            @PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return Mono.fromCallable(() -> {
                    progress.requireCompletedLearnUnit(journeyId, learnUnitCode);
                    curriculum.ensureLearnUnitContent(journeyId, learnUnitCode);
                    return AssessmentResponse.from(
                            assessments.createLearnUnitPracticeAssessment(journeyId, learnUnitCode));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 创建或恢复 Chapter 的固定 synthesis 题集。 */
    @PostMapping("/journeys/{journeyId}/chapters/{chapterCode}/synthesis")
    public Mono<AssessmentResponse> chapterSynthesis(
            @PathVariable String journeyId, @PathVariable String chapterCode) {
        return Mono.fromCallable(() -> AssessmentResponse.from(
                        assessments.createChapterSynthesis(journeyId, chapterCode)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Retry Chapter synthesis，保留原 Assessment 和固定题集。 */
    @PostMapping("/journeys/{journeyId}/chapters/{chapterCode}/synthesis/retry")
    public Mono<AssessmentResponse> retryChapterSynthesis(
            @PathVariable String journeyId, @PathVariable String chapterCode) {
        return Mono.fromCallable(() -> AssessmentResponse.from(
                        assessments.retryChapterSynthesis(journeyId, chapterCode)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Retry a failed Attempt without replacing its Assessment or fixed Question set. */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/retry")
    public AssessmentResponse retryLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return AssessmentResponse.from(assessments.retry(journeyId, learnUnitCode));
    }

    /** 跳过当前 Path 节点，并保留跳过历史。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/skip")
    public LearnUnitResponse skipLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        progress.skipLearnUnit(journeyId, learnUnitCode);
        return learnUnit(journeyId, learnUnitCode);
    }

    /** Confirm and recover the next server-selected LearnUnit after a closed item. */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/next")
    public JourneyDetailResponse nextLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        progress.nextLearnUnit(journeyId, learnUnitCode);
        return journey(journeyId);
    }

    /** 查询 LearnUnit 内容、进度和历史评估。 */
    @GetMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}")
    public LearnUnitResponse learnUnit(
            @PathVariable String journeyId, @PathVariable String learnUnitCode) {
        LearningJourney journey = journeys.get(journeyId);
        LearnUnit learnUnit = learning.listLearnUnitsForJourney(journeyId).stream()
                .filter(value -> value.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit not found: " + learnUnitCode));
        return new LearnUnitResponse(
                journeyId, learnUnit, learning.findPathItem(journeyId, learnUnitCode).orElse(null),
                learning.listAttemptsForLearnUnit(journeyId, learnUnitCode),
                learning.listQuestionAttemptsForLearnUnit(journeyId, learnUnitCode).stream()
                        .map(attempt -> publicQuestionAttempt(attempt, false)).toList());
    }

    /** 查询指定 LearnUnit 的全部评估尝试。 */
    @GetMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/attempts")
    public List<AssessmentAttempt> attempts(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        journeys.get(journeyId);
        return learning.listAttemptsForLearnUnit(journeyId, learnUnitCode);
    }

    /** 为 Journey LearnUnit 创建或复用 Tutor Session。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/tutor")
    public Mono<TutorSessionResponse> tutorSession(
            @PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return Mono.fromCallable(() -> tutorSessions.open(journeyId, learnUnitCode))
                .subscribeOn(Schedulers.boundedElastic())
                .map(tutorSession -> new TutorSessionResponse(
                        SessionResponse.from(tutorSession.session()),
                        tutorSession.journeyId(),
                        tutorSession.learnUnitCode()));
    }

    private LearningPhase parsePhase(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("phase is required");
        try {
            return LearningPhase.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown learning phase: " + value, error);
        }
    }

    private static QuestionAttempt publicQuestionAttempt(QuestionAttempt attempt, boolean draft) {
        return new QuestionAttempt(
                attempt.questionId(), attempt.assessmentAttemptId(), attempt.answerJson(),
                draft ? null : attempt.score(), attempt.maxScore(), draft ? null : attempt.feedback(),
                draft ? null : attempt.correct(), attempt.submittedCode(), null, attempt.selectedOptionIdsJson());
    }

    /** 将 Coding 评分失败转换为 422，保留可重试的答案草稿。 */
    @ExceptionHandler(AssessmentService.AssessmentEvaluationException.class)
    public ResponseEntity<Map<String, String>> evaluationError(AssessmentService.AssessmentEvaluationException error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", error.getMessage()));
    }

    /** 将请求参数或资源不存在错误转换为 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    /** 将当前状态不允许执行的操作转换为 409。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }

    /**
     * 创建 Journey 时提交的学习者资料。
     *
     * @param languageCode 用户提出的目标语言名称或编码；不存在时按需生成课程目录
     * @param goal Journey 名称或目标摘要
     * @param primaryLanguage 学习者已有的主要编程语言
     * @param experienceYears 相关经验年数
     * @param selfDescription 当前水平补充
     * @param learningGoal 具体学习目标
     */
    public record CreateJourneyRequest(
            String languageCode,
            String goal,
            String primaryLanguage,
            Integer experienceYears,
            String selfDescription,
            String learningGoal) {
    }

    public record JourneyDraftStartResponse(String runId) {
    }

    public record JourneyDraftGuidanceRequest(String content) {
    }

    public record JourneyDraftAck(String status) {
    }

    public record GuidedPracticeRequest(String response) {
    }

    /**
     * 保存一道题的答案；分数由后端计算，不接受客户端提交。
     *
     * @param questionId 题目编码
     * @param selectedOptionIds 选择题选项编码
     * @param submittedCode Coding 题代码
     */
    public record AnswerRequest(
            String questionId,
            List<String> selectedOptionIds,
            String submittedCode) {
    }

    /**
     * Journey 详情及其可恢复的 Profile、Path 和 LearnUnit 状态。
     *
     * @param journey Journey 基本信息
     * @param profile 学习者背景
     * @param chapters 按课程顺序排列的 Chapter 及其 LearnUnit、粗粒度进度
     * @param path 完整 Path，包括已完成和已跳过历史
     */
    public record JourneyDetailResponse(
            LearningJourney journey,
            LearnerProfile profile,
            List<ChapterDetail> chapters,
            List<LearningPathItem> path) {
    }

    /** Journey 详情中的一个 Chapter 进度块。 */
    public record ChapterDetail(
            Chapter chapter,
            List<LearnUnit> learnUnits,
            List<LearningPathItem> path,
            int completedCount,
            int skippedCount,
            int unresolvedCount,
            boolean synthesisAvailable,
            boolean synthesisCompleted,
            String synthesisAssessmentId) {

        public ChapterDetail(
                Chapter chapter,
                List<LearnUnit> learnUnits,
                List<LearningPathItem> path,
                int completedCount,
                int skippedCount) {
            this(chapter, learnUnits, path, completedCount, skippedCount, 0, false, false, null);
        }
    }

    /**
     * LearnUnit 页面所需的教学内容、Path 状态和历史评估。
     *
     * @param journeyId 所属 Journey
     * @param learnUnit Journey 专属的教学内容
     * @param pathItem 该 LearnUnit 在 Path 中的节点
     * @param attempts 该 LearnUnit 的历史评估尝试
     * @param questionAttempts 已完成 Attempt 的逐题反馈
     */
    public record LearnUnitResponse(
            String journeyId,
            LearnUnit learnUnit,
            LearningPathItem pathItem,
            List<AssessmentAttempt> attempts,
            List<QuestionAttempt> questionAttempts) {
    }

    /**
     * Assessment 页面响应，包含固定题集和未完成答案以支持恢复。
     *
     * @param assessment 评估定义
     * @param questions 对外公开的题目配置，已移除正确答案
     * @param openAttempt 当前进行中的 Attempt
     * @param attempts 该评估的历史 Attempt
     * @param questionAttempts 当前 Attempt 的逐题答案
     */
    public record AssessmentResponse(
            Assessment assessment,
            List<QuestionResponse> questions,
            AssessmentAttempt openAttempt,
            List<AssessmentAttempt> attempts,
            List<QuestionAttempt> questionAttempts) {

        static AssessmentResponse from(AssessmentService.AssessmentState state) {
            return new AssessmentResponse(
                    state.assessment(), state.questions().stream().map(QuestionResponse::from).toList(),
                    state.openAttempt(), state.attempts(), publicQuestionAttempts(state));
        }

        private static List<QuestionAttempt> publicQuestionAttempts(AssessmentService.AssessmentState state) {
            return state.questionAttempts().stream()
                    .map(attempt -> publicQuestionAttempt(attempt, state.openAttempt() != null))
                    .toList();
        }
    }

    /**
     * 对外公开的题目 DTO，不泄露选择题正确答案。
     *
     * @param id 题目编码
     * @param learnUnitCode 所属 LearnUnit
     * @param type 题型
     * @param difficulty 难度
     * @param prompt 题干
     * @param points 题目满分
     * @param configJson 公开题目配置
     * @param rubricJson 对外始终为空；评分标准属于服务端评估器私有数据
     * @param language Coding 语言
     * @param starterCode 起始代码
     * @param referenceConceptsJson 参考概念
     */
    public record QuestionResponse(
            String id,
            String learnUnitCode,
            String chapterCode,
            QuestionType type,
            int difficulty,
            String prompt,
            int points,
            String configJson,
            String rubricJson,
            String language,
            String starterCode,
            String referenceConceptsJson) {

        static QuestionResponse from(Question question) {
            return new QuestionResponse(
                    question.id(), question.learnUnitCode(), question.chapterCode(), question.type(), question.difficulty(), question.prompt(),
                    question.points(), publicConfig(question.configJson()), null, question.language(),
                    question.starterCode(), question.referenceConceptsJson());
        }

        private static String publicConfig(String configJson) {
            if (configJson == null) return null;
            try {
                JsonNode config = MAPPER.readTree(configJson);
                if (config.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) config).remove("correctOptionIds");
                return config.toString();
            } catch (Exception error) {
                throw new IllegalStateException("Invalid question config", error);
            }
        }
    }

    /**
     * Assessment 提交结果，返回总体分数和诊断分 LearnUnit 结果。
     *
     * @param assessment 已完成的 Assessment
     * @param attempt 本次 Attempt
     * @param score 总分摘要
     * @param passed 是否通过
     * @param learnUnitResults Diagnostic 的分 LearnUnit 结果
     * @param questionAttempts 本次逐题评分结果
     */
    public record AssessmentResultResponse(
            Assessment assessment,
            AssessmentAttempt attempt,
            AssessmentScore score,
            boolean passed,
            List<AssessmentService.DiagnosticLearnUnitResult> learnUnitResults,
            List<QuestionAttempt> questionAttempts,
            int passScore,
            Integer codingPassScore,
            String reviewLearnUnitCode,
            boolean chapterCompleted) {

        static AssessmentResultResponse from(AssessmentService.AssessmentSubmission submission) {
            return new AssessmentResultResponse(
                    submission.assessment(), submission.attempt(), submission.score(), submission.passed(),
                    submission.learnUnitResults(), submission.questionAttempts().stream()
                            .map(attempt -> publicQuestionAttempt(attempt, false)).toList(), submission.passScore(),
                    submission.codingPassScore(), submission.reviewLearnUnitCode(), submission.chapterCompleted());
        }
    }

    /**
     * Learning LearnUnit 与既有 Tutor Session 的关联响应。
     *
     * @param session 可继续使用的 Phase 1 Session
     * @param journeyId Journey 编码
     * @param learnUnitCode 当前 LearnUnit 编码
     */
    public record TutorSessionResponse(SessionResponse session, String journeyId, String learnUnitCode) {
    }

}
