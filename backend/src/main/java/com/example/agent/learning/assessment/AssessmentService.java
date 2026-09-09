package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                    Set<String> eligibleCodes = learnUnits.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
                    List<Question> available = repository.listDiagnosticQuestionsForJourney(journeyId).stream()
                            .filter(question -> eligibleCodes.contains(question.learnUnitCode()))
                            .toList();
                    LearnerProfile profile = repository.findProfile(journeyId)
                            .orElse(new LearnerProfile(journeyId, "", null, "", journey.goal()));
                    List<Question> selected = available.isEmpty()
                            ? planQuestions(language, learnUnits, available, profile)
                            : normalize(available, learnUnits, available);
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

    @Transactional
    public AssessmentState createLearnUnitAssessment(String journeyId, String learnUnitCode) {
        var journey = requireJourney(journeyId);
        LearnUnit learnUnit = repository.listLearnUnitsForJourney(journeyId).stream()
                .filter(candidate -> candidate.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("learnUnit does not belong to journey: " + learnUnitCode));
        return repository.findLatestLearnUnitAssessment(journeyId, learnUnitCode)
                .map(this::state)
                .orElseGet(() -> {
                    LearningLanguage language = repository.findLanguage(journey.languageCode()).orElseThrow();
                    List<Question> available = repository.listQuestionsForLearnUnit(learnUnitCode);
                    LearnerProfile profile = repository.findProfile(journeyId)
                            .orElse(new LearnerProfile(journeyId, "", null, "", journey.goal()));
                    List<Question> questions = available.isEmpty()
                            ? planQuestions(language, List.of(learnUnit), available, profile)
                            : normalize(available, List.of(learnUnit), available);
                    if (questions.isEmpty()) throw new IllegalStateException("learnUnit has no questions: " + learnUnitCode);
                    insertNewQuestions(questions, available);
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
        if (assessment.type() == AssessmentType.LEARN_UNIT) progress.markAssessing(assessment.journeyId(), assessment.learnUnitCode());
        return state(requireAssessment(assessmentId));
    }

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
            if (question.type() == QuestionType.CODING && current.score() == null) {
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
        if (assessment.type() == AssessmentType.DIAGNOSTIC) {
            learnUnitResults = diagnosticResults(assessment.journeyId(), questions, completedQuestions);
            passed = learnUnitResults.stream().allMatch(DiagnosticLearnUnitResult::passed);
            progress.generatePath(assessment.journeyId());
        } else {
            LearnUnit learnUnit = repository.findLearnUnit(assessment.learnUnitCode()).orElseThrow();
            passed = passPolicy.passed(score, learnUnit);
            progress.recordLearnUnitAssessment(assessment.journeyId(), assessment.learnUnitCode(), score, passed);
        }
        Instant completedAt = Instant.now();
        repository.updateAttempt(attempt.id(), score.choiceScore(), score.codingScore(), score.totalScore(), passed, completedAt);
        repository.updateAssessment(assessment.id(), AssessmentStatus.COMPLETED, completedAt);
        return new AssessmentSubmission(
                requireAssessment(assessmentId), repository.findAttempt(attempt.id()).orElseThrow(), score, passed,
                learnUnitResults, completedQuestions);
    }

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
            LearnerProfile profile) {
        try {
            return normalize(llmPlanner.plan(language, learnUnits, available, profile), learnUnits, available);
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

    private List<Question> normalize(List<Question> proposed, List<LearnUnit> learnUnits, List<Question> available) {
        if (proposed == null || proposed.isEmpty()) throw new IllegalStateException("Generated question set is empty");
        Map<String, Question> byId = new HashMap<>();
        available.forEach(question -> byId.put(question.id(), question));
        List<Question> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Question question : proposed) {
            Question existing = byId.get(question.id());
            if (existing != null && !existing.equals(question)) throw new IllegalArgumentException("Existing question was changed");
            if (!learnUnits.stream().anyMatch(learnUnit -> learnUnit.code().equals(question.learnUnitCode()))) {
                throw new IllegalArgumentException("Question belongs to an unknown learnUnit");
            }
            LearnUnit learnUnit = learnUnits.stream()
                    .filter(candidate -> candidate.code().equals(question.learnUnitCode()))
                    .findFirst().orElseThrow();
            if (question.type() == QuestionType.CODING && learnUnit.minCodingScore() == null) {
                throw new IllegalArgumentException("Coding question has no coding learning objective: " + learnUnit.code());
            }
            if (existing == null) QuestionStructureValidator.validate(question, learnUnit);
            if (ids.add(question.id())) result.add(question);
        }
        for (LearnUnit learnUnit : learnUnits) {
            for (QuestionType type : learnUnit.minCodingScore() == null
                    ? List.of(QuestionType.MULTIPLE_CHOICE)
                    : List.of(QuestionType.MULTIPLE_CHOICE, QuestionType.CODING)) {
                boolean covered = result.stream().anyMatch(question -> question.learnUnitCode().equals(learnUnit.code()) && question.type() == type);
                if (!covered) {
                    available.stream()
                            .filter(question -> question.learnUnitCode().equals(learnUnit.code()) && question.type() == type)
                            .findFirst()
                            .ifPresent(question -> {
                                if (ids.add(question.id())) result.add(question);
                            });
                }
            }
        }
        for (LearnUnit learnUnit : learnUnits) {
            boolean hasChoice = result.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code()) && question.type() == QuestionType.MULTIPLE_CHOICE);
            boolean hasCoding = result.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code()) && question.type() == QuestionType.CODING);
            if (!hasChoice || learnUnit.minCodingScore() != null && !hasCoding) {
                throw new IllegalStateException("Diagnostic coverage is incomplete for " + learnUnit.code());
            }
        }
        return result;
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
            return new QuestionAttempt(
                    current.questionId(), current.assessmentAttemptId(), current.answerJson(), evaluation.totalScore(),
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
            List<QuestionAttempt> questionAttempts) {
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
