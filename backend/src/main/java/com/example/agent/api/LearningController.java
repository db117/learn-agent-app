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
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.tutor.TutorSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
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
    private final AppProperties properties;

    public LearningController(
            LearningRepository learning,
            CurriculumService curriculum,
            LearningJourneyService journeys,
            ProgressService progress,
            AssessmentService assessments,
            TutorSessionService tutorSessions,
            AppProperties properties) {
        this.learning = learning;
        this.curriculum = curriculum;
        this.journeys = journeys;
        this.progress = progress;
        this.assessments = assessments;
        this.tutorSessions = tutorSessions;
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

    /** 创建 Journey 和学习者画像。 */
    @PostMapping("/journeys")
    public LearningJourney createJourney(@RequestBody CreateJourneyRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        return journeys.create(properties.userId(), request.languageCode(), request.goal(), request.primaryLanguage(),
                request.experienceYears(), request.selfDescription(), request.learningGoal());
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
        return new JourneyDetailResponse(
                journey, learning.findProfile(id).orElse(null), learning.listPath(id));
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

    /** 创建或恢复 Journey 的固定题集诊断。 */
    @PostMapping("/journeys/{id}/diagnostic")
    public AssessmentResponse diagnostic(@PathVariable String id) {
        return AssessmentResponse.from(assessments.createDiagnostic(id));
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
    public LearnUnitResponse startLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        progress.startLearnUnit(journeyId, learnUnitCode);
        return learnUnit(journeyId, learnUnitCode);
    }

    /** Continue the server-selected current LearnUnit after a restart or result screen. */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/continue")
    public LearnUnitResponse continueLearnUnit(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        progress.continueLearnUnit(journeyId, learnUnitCode);
        return learnUnit(journeyId, learnUnitCode);
    }

    /** 创建或恢复指定 LearnUnit 的固定题集评估。 */
    @PostMapping("/journeys/{journeyId}/learn-units/{learnUnitCode}/assessment")
    public AssessmentResponse learnUnitAssessment(@PathVariable String journeyId, @PathVariable String learnUnitCode) {
        return AssessmentResponse.from(assessments.createLearnUnitAssessment(journeyId, learnUnitCode));
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
                learning.listQuestionAttemptsForLearnUnit(journeyId, learnUnitCode));
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
     * @param path 完整 Path，包括已完成和已跳过历史
     */
    public record JourneyDetailResponse(
            LearningJourney journey,
            LearnerProfile profile,
            List<LearningPathItem> path) {
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
                    state.openAttempt(), state.attempts(), state.questionAttempts());
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
     * @param rubricJson Coding 评分标准
     * @param language Coding 语言
     * @param starterCode 起始代码
     * @param referenceConceptsJson 参考概念
     */
    public record QuestionResponse(
            String id,
            String learnUnitCode,
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
                    question.id(), question.learnUnitCode(), question.type(), question.difficulty(), question.prompt(),
                    question.points(), publicConfig(question.configJson()), question.rubricJson(), question.language(),
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
            Integer codingPassScore) {

        static AssessmentResultResponse from(AssessmentService.AssessmentSubmission submission) {
            return new AssessmentResultResponse(
                    submission.assessment(), submission.attempt(), submission.score(), submission.passed(),
                    submission.learnUnitResults(), submission.questionAttempts(), submission.passScore(),
                    submission.codingPassScore());
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
