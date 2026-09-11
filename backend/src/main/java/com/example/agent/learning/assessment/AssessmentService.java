package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 评估编排服务。
 *
 * <p>负责固定题集、保存答案草稿、调用确定性/LLM 评分器并写入 Attempt 历史；评分完成后再委托
 * {@link ProgressService} 更新 LearnUnit 和学习路径状态。本服务不修改题目定义。
 */
@Service
public class AssessmentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_DIAGNOSTIC_EVIDENCE = 2;

    private final LearningRepository repository;
    private final ProgressService progress;
    private final AssessmentScoreEngine scoreEngine;
    private final LearnUnitPassPolicy passPolicy;
    private final MultipleChoiceEvaluator multipleChoice = new MultipleChoiceEvaluator();
    private final CodingAnswerEvaluator codingEvaluator;
    private final DiagnosticQuestionPlanner llmPlanner;

    public AssessmentService(
            LearningRepository repository,
            ProgressService progress,
            AssessmentScoreEngine scoreEngine,
            LearnUnitPassPolicy passPolicy,
            CodingAnswerEvaluator codingEvaluator,
            @Qualifier("llmDiagnosticQuestionPlanner") DiagnosticQuestionPlanner llmPlanner) {
        this.repository = repository;
        this.progress = progress;
        this.scoreEngine = scoreEngine;
        this.passPolicy = passPolicy;
        this.codingEvaluator = codingEvaluator;
        this.llmPlanner = llmPlanner;
    }

    /**
     * 创建或读取 Journey 的初始诊断评估。
     *
     * <p>优先复用已固定的评估；首次创建时先筛选可诊断 LearnUnit 和已有题目，不满足覆盖要求时调用
     * LLM 规划题目，校验后以 insert-only 方式保存题目并固定题目顺序。</p>
     */
    @Transactional
    public AssessmentState createDiagnostic(String journeyId) {
        return repository.findDiagnosticAssessment(journeyId)
                .map(this::state)
                .orElseGet(() -> {
                    var journey = requireJourney(journeyId);
                    LearningLanguage language = repository.findLanguage(journey.languageCode()).orElseThrow();
                    List<LearnUnit> learnUnits = repository.listLearnUnitsForJourney(journeyId).stream()
                            .filter(LearnUnit::diagnosticEligible)
                            .toList();
                    if (learnUnits.isEmpty()) throw new IllegalStateException("No diagnostic LearnUnits are available");
                    Set<String> eligibleCodes = learnUnits.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
                    List<Question> available = repository.listDiagnosticQuestionsForJourney(journeyId).stream()
                            .filter(question -> eligibleCodes.contains(question.learnUnitCode()))
                            .toList();
                    LearnerProfile profile = repository.findProfile(journeyId)
                            .orElse(new LearnerProfile(journeyId, "", null, "", journey.goal()));
                    List<Question> selected = hasCoverage(available, learnUnits, MIN_DIAGNOSTIC_EVIDENCE)
                            ? normalize(available, learnUnits, available, MIN_DIAGNOSTIC_EVIDENCE)
                            : planQuestions(language, learnUnits, available, profile, MIN_DIAGNOSTIC_EVIDENCE);
                    if (selected.isEmpty()) throw new IllegalStateException("No diagnostic questions are available");
                    insertNewQuestions(selected, available);
                    Instant now = Instant.now();
                    Assessment assessment = new Assessment(
                            UUID.randomUUID().toString(), journeyId, null, AssessmentType.DIAGNOSTIC,
                            AssessmentStatus.CREATED, now, null);
                    repository.insertAssessment(assessment);
                    for (int index = 0; index < selected.size(); index++) {
                        repository.insertAssessmentQuestion(assessment.id(), selected.get(index).id(), index);
                    }
                    return state(assessment);
                });
    }

    /**
     * 创建或读取指定 LearnUnit 的评估。
     *
     * <p>只使用 LearnUnit 正文生成阶段已经持久化的独立检查题目。评估创建后题集不会因后续题库变化而改变。</p>
     */
    @Transactional
    public AssessmentState createLearnUnitAssessment(String journeyId, String learnUnitCode) {
        progress.requireCurrentLearnUnit(journeyId, learnUnitCode);
        return createLearnUnitAssessmentInternal(journeyId, learnUnitCode);
    }

    /** 显式 practice 入口，允许对已完成 LearnUnit 使用同一套固定独立检查题。 */
    @Transactional
    public AssessmentState createLearnUnitPracticeAssessment(String journeyId, String learnUnitCode) {
        progress.requireCompletedLearnUnit(journeyId, learnUnitCode);
        return createLearnUnitAssessmentInternal(journeyId, learnUnitCode);
    }

    private AssessmentState createLearnUnitAssessmentInternal(String journeyId, String learnUnitCode) {
        requireJourney(journeyId);
        LearnUnit learnUnit = repository.listLearnUnitsForJourney(journeyId).stream()
                .filter(candidate -> candidate.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("learnUnit does not belong to journey: " + learnUnitCode));
        return repository.findLatestLearnUnitAssessment(journeyId, learnUnitCode)
                .map(this::state)
                .orElseGet(() -> {
                    List<Question> available = repository.listQuestionsForLearnUnit(learnUnitCode).stream()
                            .filter(question -> question.role() == QuestionRole.INDEPENDENT)
                            .toList();
                    if (available.isEmpty()) {
                        throw new IllegalStateException("learnUnit has no persisted independent questions: " + learnUnitCode);
                    }
                    List<Question> questions = normalize(available, List.of(learnUnit), available, 1);
                    Instant now = Instant.now();
                    Assessment assessment = new Assessment(
                            UUID.randomUUID().toString(), journeyId, learnUnitCode, AssessmentType.LEARN_UNIT,
                            AssessmentStatus.CREATED, now, null);
                    repository.insertAssessment(assessment);
                    for (int index = 0; index < questions.size(); index++) {
                        repository.insertAssessmentQuestion(assessment.id(), questions.get(index).id(), index);
                    }
                    return state(assessment);
                });
    }

    /** 创建或读取一个 Chapter 的固定 synthesis 题集；不会触发 LearnUnit 正文生成。 */
    @Transactional
    public AssessmentState createChapterSynthesis(String journeyId, String chapterCode) {
        return repository.findLatestChapterSynthesisAssessment(journeyId, chapterCode)
                .map(this::state)
                .orElseGet(() -> {
                    requireJourney(journeyId);
                    Chapter chapter = repository.listChaptersForJourney(journeyId).stream()
                            .filter(value -> value.code().equals(chapterCode))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Chapter is not in the Journey: " + chapterCode));
                    progress.requireChapterSynthesisEligible(journeyId, chapterCode);
                    List<LearnUnit> learnUnits = repository.listLearnUnitsForJourney(journeyId).stream()
                            .filter(unit -> chapterCode.equals(unit.chapterCode()))
                            .toList();
                    List<Question> available = repository.listQuestionsForChapter(journeyId, chapterCode);
                    List<Question> selected = available.isEmpty()
                            ? synthesisQuestions(chapter, learnUnits) : validateSynthesisQuestions(available, chapterCode);
                    insertNewQuestions(selected, available);
                    Assessment assessment = new Assessment(
                            UUID.randomUUID().toString(), journeyId, null, chapterCode,
                            AssessmentType.CHAPTER_SYNTHESIS, AssessmentStatus.CREATED, Instant.now(), null);
                    repository.insertAssessment(assessment);
                    for (int index = 0; index < selected.size(); index++) {
                        repository.insertAssessmentQuestion(assessment.id(), selected.get(index).id(), index);
                    }
                    return state(assessment);
                });
    }

    /**
     * 开始一次 Assessment Attempt；已有未完成 Attempt 时保持幂等并直接返回当前状态。
     *
     * <p>新 Attempt 会递增序号、更新评估状态；LearnUnit 评估还会通知进度服务进入评估阶段。</p>
     */
    @Transactional
    public AssessmentState start(String assessmentId) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.type() == AssessmentType.DIAGNOSTIC && assessment.status() == AssessmentStatus.COMPLETED) {
            throw new IllegalArgumentException("diagnostic is already completed");
        }
        var open = repository.findOpenAttempt(assessmentId);
        if (open.isPresent()) return state(assessment);
        int number = repository.nextAttemptNumber(assessmentId);
        Instant now = Instant.now();
        repository.insertAttempt(new AssessmentAttempt(
                UUID.randomUUID().toString(), assessment.id(), assessment.journeyId(), assessment.learnUnitCode(),
                number, null, null, null, null, now, null));
        repository.updateAssessment(assessment.id(), AssessmentStatus.IN_PROGRESS, null);
        if (assessment.type() == AssessmentType.LEARN_UNIT) {
            var pathItem = progress.pathItem(assessment.journeyId(), assessment.learnUnitCode());
            if (pathItem.status() == com.example.agent.learning.path.LearningPathItemStatus.COMPLETED) {
                progress.requireCompletedLearnUnit(assessment.journeyId(), assessment.learnUnitCode());
            } else {
                progress.markAssessing(assessment.journeyId(), assessment.learnUnitCode());
            }
        }
        return state(requireAssessment(assessmentId));
    }

    /** 为同一个当前 LearnUnit Assessment 创建一次失败后的新 Attempt。 */
    @Transactional
    public AssessmentState retry(String journeyId, String learnUnitCode) {
        var pathItem = progress.pathItem(journeyId, learnUnitCode);
        if (pathItem.status() == com.example.agent.learning.path.LearningPathItemStatus.COMPLETED) {
            progress.requireCompletedLearnUnit(journeyId, learnUnitCode);
        } else {
            progress.requireCurrentLearnUnit(journeyId, learnUnitCode);
        }
        Assessment assessment = repository.findLatestLearnUnitAssessment(journeyId, learnUnitCode)
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit has no assessment to retry: " + learnUnitCode));
        AssessmentState current = state(assessment);
        if (current.openAttempt() != null) return current;
        AssessmentAttempt latest = current.attempts().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit has no completed attempt to retry: " + learnUnitCode));
        if (latest.completedAt() == null || !Boolean.FALSE.equals(latest.passed())) {
            throw new IllegalArgumentException("LearnUnit assessment is not retryable: " + learnUnitCode);
        }
        return start(assessment.id());
    }

    /** 为同一个 Chapter synthesis Assessment 创建一次失败后的新 Attempt。 */
    @Transactional
    public AssessmentState retryChapterSynthesis(String journeyId, String chapterCode) {
        Assessment assessment = repository.findLatestChapterSynthesisAssessment(journeyId, chapterCode)
                .orElseThrow(() -> new IllegalArgumentException("Chapter has no synthesis to retry: " + chapterCode));
        AssessmentState current = state(assessment);
        if (current.openAttempt() != null) return current;
        AssessmentAttempt latest = current.attempts().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Chapter synthesis has no completed attempt to retry: " + chapterCode));
        if (latest.completedAt() == null || !Boolean.FALSE.equals(latest.passed())) {
            throw new IllegalArgumentException("Chapter synthesis is not retryable: " + chapterCode);
        }
        return start(assessment.id());
    }

    /**
     * 保存一道题的答案，并对选择题立即执行确定性评分。
     *
     * <p>Coding 题只保存代码和原始答案，提交评估时再调用评分器，以便把异步或失败的评分纳入统一收尾流程。</p>
     */
    @Transactional
    public AssessmentState answer(String assessmentId, QuestionAnswer answer) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.status() != AssessmentStatus.IN_PROGRESS) throw new IllegalArgumentException("assessment is not in progress");
        AssessmentAttempt attempt = openAttempt(assessmentId);
        Question question = question(assessmentId, answer.questionId());
        QuestionAttempt result;
        if (question.type() == QuestionType.MULTIPLE_CHOICE) {
            MultipleChoiceEvaluator.Result evaluated = multipleChoice.evaluate(question, answer.selectedOptionIds());
            result = new QuestionAttempt(
                    question.id(), attempt.id(), json(answer), evaluated.score(), question.points(),
                    evaluated.correct() ? "Correct." : "Review this concept and try again.", evaluated.correct(),
                    null, null, json(answer.selectedOptionIds()));
        } else {
            result = new QuestionAttempt(
                    question.id(), attempt.id(), json(answer), null, question.points(), null, null,
                    answer.submittedCode(), null, null);
        }
        repository.saveQuestionAttempt(result);
        return state(assessment);
    }

    /**
     * 补齐未回答题目、评分 Coding 题、计算总分，并提交评估和学习路径结果。
     *
     * <p>诊断评估会按 LearnUnit 汇总通过情况并重新生成路径；LearnUnit 评估会依据通过策略更新当前节点。
     * 最后才写入 Attempt 和 Assessment 的终态，评分异常按约定保留可重试状态。</p>
     */
    @Transactional(noRollbackFor = AssessmentEvaluationException.class)
    public AssessmentSubmission submit(String assessmentId) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.status() != AssessmentStatus.IN_PROGRESS) throw new IllegalArgumentException("assessment is not in progress");
        AssessmentAttempt attempt = openAttempt(assessmentId);
        List<Question> questions = repository.listQuestionsForAssessment(assessmentId);
        Map<String, QuestionAttempt> attempts = new LinkedHashMap<>();
        repository.listQuestionAttempts(attempt.id()).forEach(value -> attempts.put(value.questionId(), value));
        for (Question question : questions) {
            QuestionAttempt current = attempts.get(question.id());
            if (current == null) {
                current = emptyAttempt(question, attempt.id());
                repository.saveQuestionAttempt(current);
            }
            if (question.type() == QuestionType.CODING
                    && (current.score() == null || current.evaluationJson() == null || current.evaluationJson().isBlank())) {
                try {
                    current = evaluateCoding(question, current);
                } catch (AssessmentEvaluationException error) {
                    if (assessment.type() == AssessmentType.LEARN_UNIT) {
                        progress.markAssessmentFailed(assessment.journeyId(), assessment.learnUnitCode());
                    }
                    throw error;
                }
                repository.saveQuestionAttempt(current);
            }
            attempts.put(question.id(), current);
        }

        List<QuestionAttempt> completedQuestions = new ArrayList<>(attempts.values());
        Map<String, QuestionType> types = new HashMap<>();
        for (Question question : questions) types.put(question.id(), question.type());
        AssessmentScore score = scoreEngine.scoreAttempts(completedQuestions, types);
        boolean passed;
        List<DiagnosticLearnUnitResult> learnUnitResults = List.of();
        int passScore;
        Integer codingPassScore;
        String reviewLearnUnitCode = null;
        boolean chapterCompleted = false;
        if (assessment.type() == AssessmentType.DIAGNOSTIC) {
            learnUnitResults = diagnosticResults(assessment.journeyId(), questions, completedQuestions);
            passed = learnUnitResults.stream().allMatch(DiagnosticLearnUnitResult::passed);
            passScore = LearnUnitPassPolicy.DIAGNOSTIC_PASS_SCORE;
            codingPassScore = null;
            progress.generatePath(assessment.journeyId());
        } else if (assessment.type() == AssessmentType.CHAPTER_SYNTHESIS) {
            passed = passPolicy.synthesisPassed(score);
            passScore = LearnUnitPassPolicy.SYNTHESIS_PASS_SCORE;
            codingPassScore = null;
            List<LearnUnit> chapterUnits = repository.listLearnUnitsForJourney(assessment.journeyId()).stream()
                    .filter(unit -> assessment.chapterCode().equals(unit.chapterCode()))
                    .toList();
            ProgressService.ChapterSynthesisOutcome outcome = progress.recordChapterSynthesis(
                    assessment.journeyId(), assessment.chapterCode(), score, passed,
                    coveredLearnUnitCodes(questions, chapterUnits));
            reviewLearnUnitCode = outcome.firstWeakLearnUnitCode();
            chapterCompleted = outcome.chapterCompleted();
        } else {
            LearnUnit learnUnit = repository.findLearnUnit(assessment.learnUnitCode()).orElseThrow();
            passed = passPolicy.passed(score, learnUnit);
            passScore = learnUnit.passScore();
            codingPassScore = score.hasCodingQuestions() ? learnUnit.minCodingScore() : null;
            progress.recordLearnUnitAssessment(assessment.journeyId(), assessment.learnUnitCode(), score, passed);
        }
        Instant completedAt = Instant.now();
        repository.updateAttempt(attempt.id(), score.choiceScore(), score.codingScore(), score.totalScore(), passed, completedAt);
        repository.updateAssessment(assessment.id(), AssessmentStatus.COMPLETED, completedAt);
        return new AssessmentSubmission(
                requireAssessment(assessmentId), repository.findAttempt(attempt.id()).orElseThrow(), score, passed,
                learnUnitResults, completedQuestions, passScore, codingPassScore,
                reviewLearnUnitCode, chapterCompleted);
    }

    /** 从数据库重新组装评估、固定题集、当前 Attempt 和历史 Attempt 的完整状态。 */
    public AssessmentState state(Assessment assessment) {
        var openAttempt = repository.findOpenAttempt(assessment.id());
        return new AssessmentState(
                assessment,
                repository.listQuestionsForAssessment(assessment.id()),
                openAttempt.orElse(null),
                repository.listAttemptsForAssessment(assessment.id()),
                openAttempt
                        .map(attempt -> repository.listQuestionAttempts(attempt.id()))
                .orElse(List.of()));
    }

    /** 调用 LLM 规划题目；模型失败直接报错，不回退到旧题库或确定性 Mock。 */
    private List<Question> planQuestions(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> available,
            LearnerProfile profile,
            int minimumEvidence) {
        try {
            return normalize(
                    llmPlanner.plan(language, learnUnits, available, profile), learnUnits, available, minimumEvidence);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to generate assessment questions", error);
        }
    }

    /** 把本次规划得到、尚未存在于题库中的题目以 insert-only 方式写入 SQLite。 */
    private void insertNewQuestions(List<Question> selected, List<Question> available) {
        for (Question question : selected) {
            if (available.stream().noneMatch(existing -> existing.id().equals(question.id()))) {
                repository.insertGeneratedQuestion(question);
            }
        }
    }

    private List<Question> validateSynthesisQuestions(List<Question> questions, String chapterCode) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("Chapter synthesis question set is empty: " + chapterCode);
        }
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (question == null || question.role() != QuestionRole.SYNTHESIS
                    || !chapterCode.equals(question.chapterCode()) || question.learnUnitCode() != null
                    || !ids.add(question.id())) {
                throw new IllegalArgumentException("Invalid Chapter synthesis question ownership: "
                        + (question == null ? "null" : question.id()));
            }
            QuestionStructureValidator.validate(question);
        }
        return questions;
    }

    private List<Question> synthesisQuestions(Chapter chapter, List<LearnUnit> learnUnits) {
        if (learnUnits.isEmpty()) throw new IllegalStateException("Chapter has no LearnUnits: " + chapter.code());
        List<Question> result = new ArrayList<>();
        for (LearnUnit learnUnit : learnUnits) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("options", List.of(
                    Map.of("id", "A", "text", "能够独立运用本章目标中的能力"),
                    Map.of("id", "B", "text", "只记住一个术语的名称")));
            config.put("correctOptionIds", List.of("A"));
            config.put("multiple", false);
            Question question = new Question(
                    "generated-synthesis-question-" + UUID.randomUUID(), null, chapter.code(),
                    QuestionType.MULTIPLE_CHOICE, 1,
                    "围绕“" + learnUnit.name() + "”，哪项表现符合 Chapter 的综合目标？", 20,
                    json(config), null, null, null, json(List.of(learnUnit.code())), false,
                    QuestionRole.SYNTHESIS);
            QuestionStructureValidator.validate(question);
            result.add(question);
        }
        return result;
    }

    private List<String> coveredLearnUnitCodes(List<Question> questions, List<LearnUnit> learnUnits) {
        Set<String> codes = learnUnits.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
        Set<String> covered = new java.util.LinkedHashSet<>();
        for (Question question : questions) {
            try {
                JsonNode concepts = MAPPER.readTree(question.referenceConceptsJson());
                if (concepts != null && concepts.isArray()) {
                    for (JsonNode concept : concepts) {
                        if (concept.isTextual() && codes.contains(concept.textValue())) covered.add(concept.textValue());
                    }
                }
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.copyOf(covered);
    }

    /**
     * 校验并补齐模型或题库提供的题目集合。
     *
     * <p>该步骤拒绝修改既有题目，检查题目归属、题型规则、诊断资格和重复项，并确保每个 LearnUnit
     * 达到所需证据数量及题型覆盖。</p>
     */
    private List<Question> normalize(
            List<Question> proposed, List<LearnUnit> learnUnits, List<Question> available, int minimumEvidence) {
        if (proposed == null || proposed.isEmpty()) throw new IllegalStateException("Generated question set is empty");
        Map<String, Question> byId = new HashMap<>();
        available.forEach(question -> byId.put(question.id(), question));
        List<Question> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Question question : proposed) {
            if (question == null) throw new IllegalArgumentException("Question is required");
            Question existing = byId.get(question.id());
            if (existing != null && !existing.equals(question)) throw new IllegalArgumentException("Existing question was changed");
            LearnUnit learnUnit = learnUnits.stream()
                    .filter(candidate -> candidate.code().equals(question.learnUnitCode()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Question belongs to an unknown learnUnit"));
            if (question.type() == QuestionType.CODING && learnUnit.minCodingScore() == null) {
                throw new IllegalArgumentException("Coding question has no coding learning objective: " + learnUnit.code());
            }
            if (minimumEvidence > 1 && question.role() != QuestionRole.DIAGNOSTIC) {
                throw new IllegalArgumentException("Diagnostic question must be eligible: " + question.id());
            }
            QuestionStructureValidator.validate(question, learnUnit);
            if (!ids.add(question.id())) throw new IllegalArgumentException("Duplicate question: " + question.id());
            result.add(question);
        }
        if (minimumEvidence > 1) {
            for (LearnUnit learnUnit : learnUnits) {
                for (QuestionType type : learnUnit.minCodingScore() == null
                        ? List.of(QuestionType.MULTIPLE_CHOICE)
                        : List.of(QuestionType.MULTIPLE_CHOICE, QuestionType.CODING)) {
                    boolean covered = result.stream().anyMatch(question -> question.learnUnitCode().equals(learnUnit.code()) && question.type() == type);
                    if (!covered) {
                        available.stream()
                                .filter(question -> question.learnUnitCode().equals(learnUnit.code()) && question.type() == type)
                                .filter(question -> ids.add(question.id()))
                                .findFirst()
                                .ifPresent(result::add);
                    }
                }
                while (result.stream().filter(question -> question.learnUnitCode().equals(learnUnit.code())).count()
                        < minimumEvidence) {
                    int before = result.size();
                    available.stream()
                            .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                            .filter(question -> ids.add(question.id()))
                            .findFirst()
                            .ifPresent(result::add);
                    if (result.size() == before) break;
                }
            }
        }
        for (Question question : result) {
            LearnUnit learnUnit = learnUnits.stream()
                    .filter(candidate -> candidate.code().equals(question.learnUnitCode()))
                    .findFirst().orElseThrow();
            if (minimumEvidence > 1 && question.role() != QuestionRole.DIAGNOSTIC) {
                throw new IllegalArgumentException("Diagnostic question must be eligible: " + question.id());
            }
            QuestionStructureValidator.validate(question, learnUnit);
        }
        for (LearnUnit learnUnit : learnUnits) {
            boolean hasChoice = result.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code()) && question.type() == QuestionType.MULTIPLE_CHOICE);
            boolean hasCoding = result.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code()) && question.type() == QuestionType.CODING);
            long evidence = result.stream()
                    .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                    .count();
            if (evidence < minimumEvidence
                    || minimumEvidence > 1 && (!hasChoice || learnUnit.minCodingScore() != null && !hasCoding)) {
                throw new IllegalStateException("Assessment coverage is incomplete for " + learnUnit.code());
            }
        }
        return result;
    }

    private boolean hasCoverage(List<Question> questions, List<LearnUnit> learnUnits, int minimumEvidence) {
        return !learnUnits.isEmpty() && learnUnits.stream().allMatch(learnUnit -> {
            long evidence = questions.stream()
                    .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                    .filter(question -> question.role() == QuestionRole.DIAGNOSTIC)
                    .count();
            boolean hasChoice = questions.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code())
                            && question.role() == QuestionRole.DIAGNOSTIC && question.type() == QuestionType.MULTIPLE_CHOICE);
            boolean hasCoding = questions.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code())
                            && question.role() == QuestionRole.DIAGNOSTIC && question.type() == QuestionType.CODING);
            return evidence >= minimumEvidence && hasChoice
                    && (learnUnit.minCodingScore() == null || hasCoding);
        });
    }

    private List<DiagnosticLearnUnitResult> diagnosticResults(
            String journeyId, List<Question> questions, List<QuestionAttempt> attempts) {
        Map<String, List<Question>> questionsByLearnUnit = new LinkedHashMap<>();
        for (Question question : questions) questionsByLearnUnit.computeIfAbsent(question.learnUnitCode(), ignored -> new ArrayList<>()).add(question);
        Map<String, QuestionAttempt> attemptsByQuestion = new HashMap<>();
        attempts.forEach(attempt -> attemptsByQuestion.put(attempt.questionId(), attempt));
        List<DiagnosticLearnUnitResult> result = new ArrayList<>();
        for (Map.Entry<String, List<Question>> entry : questionsByLearnUnit.entrySet()) {
            List<QuestionAttempt> learnUnitAttempts = entry.getValue().stream()
                    .map(question -> attemptsByQuestion.get(question.id()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, QuestionType> types = new HashMap<>();
            entry.getValue().forEach(question -> types.put(question.id(), question.type()));
            AssessmentScore score = scoreEngine.scoreAttempts(learnUnitAttempts, types);
            boolean passed = passPolicy.diagnosticPassed(score, learnUnitAttempts.size());
            progress.recordDiagnosticResult(journeyId, entry.getKey(), score, passed);
            result.add(new DiagnosticLearnUnitResult(entry.getKey(), score, passed, learnUnitAttempts.size()));
        }
        return result;
    }

    private QuestionAttempt evaluateCoding(Question question, QuestionAttempt current) {
        try {
            CodingEvaluationResult evaluation = codingEvaluator.evaluate(
                    CodingQuestion.from(question), current.submittedCode());
            int score = (int) Math.round(evaluation.totalScore() * question.points() / 100.0);
            return new QuestionAttempt(
                    current.questionId(), current.assessmentAttemptId(), current.answerJson(), score,
                    current.maxScore(), evaluation.feedback(), null, current.submittedCode(),
                    json(evaluation), current.selectedOptionIdsJson());
        } catch (RuntimeException error) {
            QuestionAttempt draft = new QuestionAttempt(
                    current.questionId(), current.assessmentAttemptId(), current.answerJson(), null, current.maxScore(),
                    "Evaluation unavailable: " + error.getMessage(), null, current.submittedCode(), null,
                    current.selectedOptionIdsJson());
            repository.saveQuestionAttempt(draft);
            throw new AssessmentEvaluationException("Coding evaluation failed; draft was preserved", error);
        }
    }

    private QuestionAttempt emptyAttempt(Question question, String attemptId) {
        QuestionAnswer answer = new QuestionAnswer(question.id(), List.of(), "");
        return new QuestionAttempt(
                question.id(), attemptId, json(answer),
                question.type() == QuestionType.MULTIPLE_CHOICE ? 0 : null, question.points(),
                question.type() == QuestionType.MULTIPLE_CHOICE ? "No option selected." : null,
                question.type() == QuestionType.MULTIPLE_CHOICE ? false : null,
                question.type() == QuestionType.CODING ? "" : null, null,
                question.type() == QuestionType.MULTIPLE_CHOICE ? "[]" : null);
    }

    private Question question(String assessmentId, String questionId) {
        return repository.listQuestionsForAssessment(assessmentId).stream()
                .filter(value -> value.id().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("question is not in assessment: " + questionId));
    }

    private AssessmentAttempt openAttempt(String assessmentId) {
        return repository.findOpenAttempt(assessmentId)
                .orElseThrow(() -> new IllegalStateException("assessment has not been started"));
    }

    private Assessment requireAssessment(String id) {
        return repository.findAssessment(id)
                .orElseThrow(() -> new IllegalArgumentException("assessment not found: " + id));
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize assessment data", error);
        }
    }

    private com.example.agent.learning.journey.LearningJourney requireJourney(String id) {
        return repository.findJourney(id)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + id));
    }

    /**
     * Assessment 当前状态，供 API 和前端恢复进行中的答案。
     *
     * @param assessment 评估定义
     * @param questions 固定题集，按 assessment_question 顺序排列
     * @param openAttempt 当前未提交的 Attempt，没有时为空
     * @param attempts 该 Assessment 的全部历史 Attempt
     * @param questionAttempts 当前 open Attempt 的逐题答案
     */
    public record AssessmentState(
            Assessment assessment,
            List<Question> questions,
            AssessmentAttempt openAttempt,
            List<AssessmentAttempt> attempts,
            List<QuestionAttempt> questionAttempts) {
    }

    /**
     * Assessment 提交后的结果。
     *
     * @param assessment 已完成的评估定义
     * @param attempt 本次已完成的 Attempt
     * @param score 总体分数摘要
     * @param passed 是否通过
     * @param learnUnitResults 诊断评估按 LearnUnit 拆分的结果；LearnUnit 评估时为空列表
     * @param questionAttempts 本次提交的逐题结果
     */
    public record AssessmentSubmission(
            Assessment assessment,
            AssessmentAttempt attempt,
            AssessmentScore score,
            boolean passed,
            List<DiagnosticLearnUnitResult> learnUnitResults,
            List<QuestionAttempt> questionAttempts,
            int passScore,
            Integer codingPassScore,
            String reviewLearnUnitCode,
            boolean chapterCompleted) {

        public AssessmentSubmission(
                Assessment assessment,
                AssessmentAttempt attempt,
                AssessmentScore score,
                boolean passed,
                List<DiagnosticLearnUnitResult> learnUnitResults,
                List<QuestionAttempt> questionAttempts,
                int passScore,
                Integer codingPassScore) {
            this(assessment, attempt, score, passed, learnUnitResults, questionAttempts, passScore,
                    codingPassScore, null, false);
        }
    }

    /**
     * Diagnostic 对单个 LearnUnit 的评分结果。
     *
     * @param learnUnitCode LearnUnit 编码
     * @param score 该 LearnUnit 题目的分数摘要
     * @param passed 是否达到诊断自动通过条件
     * @param evidenceCount 参与该 LearnUnit 判定的有效题目数量
     */
    public record DiagnosticLearnUnitResult(
            String learnUnitCode,
            AssessmentScore score,
            boolean passed,
            int evidenceCount) {
    }

    /** Coding 评分器失败时抛出的异常；事务会保留当前答案草稿以便重试。 */
    public static final class AssessmentEvaluationException extends RuntimeException {
        public AssessmentEvaluationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
