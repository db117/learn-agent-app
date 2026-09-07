package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.diagnostic.DeterministicDiagnosticQuestionPlanner;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.SkillPassPolicy;
import com.google.adk.JsonBaseModel;
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
 * {@link ProgressService} 更新技能和学习路径状态。本服务不修改题目定义。
 */
@Service
public class AssessmentService {

    private final LearningRepository repository;
    private final ProgressService progress;
    private final AssessmentScoreEngine scoreEngine;
    private final SkillPassPolicy passPolicy;
    private final MultipleChoiceEvaluator multipleChoice = new MultipleChoiceEvaluator();
    private final CodingAnswerEvaluator codingEvaluator;
    private final DiagnosticQuestionPlanner llmPlanner;
    private final DeterministicDiagnosticQuestionPlanner fallbackPlanner = new DeterministicDiagnosticQuestionPlanner();

    public AssessmentService(
            LearningRepository repository,
            ProgressService progress,
            AssessmentScoreEngine scoreEngine,
            SkillPassPolicy passPolicy,
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
                    List<LearningSkill> skills = repository.listSkillsForJourney(journeyId).stream()
                            .filter(LearningSkill::diagnosticEligible)
                            .toList();
                    List<Question> available = repository.listDiagnosticQuestionsForJourney(journeyId);
                    LearnerProfile profile = repository.findProfile(journeyId)
                            .orElse(new LearnerProfile(journeyId, "", null, "", journey.goal()));
                    List<Question> selected = planQuestions(language, skills, available, profile);
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
    public AssessmentState createSkillAssessment(String journeyId, String skillCode) {
        var journey = requireJourney(journeyId);
        LearningSkill skill = repository.listSkillsForJourney(journeyId).stream()
                .filter(candidate -> candidate.code().equals(skillCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("skill does not belong to journey: " + skillCode));
        return repository.findLatestSkillAssessment(journeyId, skillCode)
                .map(this::state)
                .orElseGet(() -> {
                    LearningLanguage language = repository.findLanguage(journey.languageCode()).orElseThrow();
                    List<Question> available = repository.listQuestionsForSkill(skillCode);
                    LearnerProfile profile = repository.findProfile(journeyId)
                            .orElse(new LearnerProfile(journeyId, "", null, "", journey.goal()));
                    List<Question> questions = planQuestions(language, List.of(skill), available, profile);
                    if (questions.isEmpty()) throw new IllegalStateException("skill has no questions: " + skillCode);
                    insertNewQuestions(questions, available);
                    Instant now = Instant.now();
                    Assessment assessment = new Assessment(
                            UUID.randomUUID().toString(), journeyId, skillCode, AssessmentType.SKILL,
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
                UUID.randomUUID().toString(), assessment.id(), assessment.journeyId(), assessment.skillCode(),
                number, null, null, null, null, now, null));
        repository.updateAssessment(assessment.id(), AssessmentStatus.IN_PROGRESS, null);
        if (assessment.type() == AssessmentType.SKILL) progress.markAssessing(assessment.journeyId(), assessment.skillCode());
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
                    question.id(), attempt.id(), JsonBaseModel.toJsonString(answer), evaluated.score(), question.points(),
                    evaluated.correct() ? "Correct." : "Review this concept and try again.", evaluated.correct(),
                    null, null, JsonBaseModel.toJsonString(answer.selectedOptionIds()));
        } else {
            result = new QuestionAttempt(
                    question.id(), attempt.id(), JsonBaseModel.toJsonString(answer), null, question.points(), null, null,
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
                current = evaluateCoding(question, current);
                repository.saveQuestionAttempt(current);
            }
            attempts.put(question.id(), current);
        }

        List<QuestionAttempt> completedQuestions = new ArrayList<>(attempts.values());
        Map<String, QuestionType> types = new HashMap<>();
        for (Question question : questions) types.put(question.id(), question.type());
        AssessmentScore score = scoreEngine.scoreAttempts(completedQuestions, types);
        boolean passed;
        List<DiagnosticSkillResult> skillResults = List.of();
        if (assessment.type() == AssessmentType.DIAGNOSTIC) {
            skillResults = diagnosticResults(assessment.journeyId(), questions, completedQuestions);
            passed = skillResults.stream().allMatch(DiagnosticSkillResult::passed);
            progress.generatePath(assessment.journeyId());
        } else {
            LearningSkill skill = repository.findSkill(assessment.skillCode()).orElseThrow();
            passed = passPolicy.passed(score, skill);
            progress.recordSkillAssessment(assessment.journeyId(), assessment.skillCode(), score, passed);
        }
        Instant completedAt = Instant.now();
        repository.updateAttempt(attempt.id(), score.choiceScore(), score.codingScore(), score.totalScore(), passed, completedAt);
        repository.updateAssessment(assessment.id(), AssessmentStatus.COMPLETED, completedAt);
        return new AssessmentSubmission(
                requireAssessment(assessmentId), repository.findAttempt(attempt.id()).orElseThrow(), score, passed,
                skillResults, completedQuestions);
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

    /**
     * 调用 LLM 规划题目；模型失败时仅允许从已有 SQLite 题库回退选择。
     *
     * <p>新题由 Java 规则校验后再持久化，已有题目不会被模型返回值覆盖。</p>
     */
    private List<Question> planQuestions(
            LearningLanguage language,
            List<LearningSkill> skills,
            List<Question> available,
            LearnerProfile profile) {
        try {
            return normalize(llmPlanner.plan(language, skills, available, profile), skills, available);
        } catch (RuntimeException ignored) {
            return normalize(fallbackPlanner.plan(language, skills, available, profile), skills, available);
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

    private List<Question> normalize(List<Question> proposed, List<LearningSkill> skills, List<Question> available) {
        Map<String, Question> byId = new HashMap<>();
        available.forEach(question -> byId.put(question.id(), question));
        List<Question> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Question question : proposed) {
            Question existing = byId.get(question.id());
            if (existing != null && !existing.equals(question)) throw new IllegalArgumentException("Existing question was changed");
            if (!skills.stream().anyMatch(skill -> skill.code().equals(question.skillCode()))) {
                throw new IllegalArgumentException("Question belongs to an unknown skill");
            }
            if (ids.add(question.id())) result.add(question);
        }
        for (LearningSkill skill : skills) {
            for (QuestionType type : List.of(QuestionType.MULTIPLE_CHOICE, QuestionType.CODING)) {
                boolean covered = result.stream().anyMatch(question -> question.skillCode().equals(skill.code()) && question.type() == type);
                if (!covered) {
                    available.stream()
                            .filter(question -> question.skillCode().equals(skill.code()) && question.type() == type)
                            .findFirst()
                            .ifPresent(question -> {
                                if (ids.add(question.id())) result.add(question);
                            });
                }
            }
        }
        for (LearningSkill skill : skills) {
            if (result.stream().noneMatch(question -> question.skillCode().equals(skill.code()) && question.type() == QuestionType.MULTIPLE_CHOICE)
                    || result.stream().noneMatch(question -> question.skillCode().equals(skill.code()) && question.type() == QuestionType.CODING)) {
                throw new IllegalStateException("Diagnostic coverage is incomplete for " + skill.code());
            }
        }
        return result;
    }

    private List<DiagnosticSkillResult> diagnosticResults(
            String journeyId, List<Question> questions, List<QuestionAttempt> attempts) {
        Map<String, List<Question>> questionsBySkill = new LinkedHashMap<>();
        for (Question question : questions) questionsBySkill.computeIfAbsent(question.skillCode(), ignored -> new ArrayList<>()).add(question);
        Map<String, QuestionAttempt> attemptsByQuestion = new HashMap<>();
        attempts.forEach(attempt -> attemptsByQuestion.put(attempt.questionId(), attempt));
        List<DiagnosticSkillResult> result = new ArrayList<>();
        for (Map.Entry<String, List<Question>> entry : questionsBySkill.entrySet()) {
            List<QuestionAttempt> skillAttempts = entry.getValue().stream()
                    .map(question -> attemptsByQuestion.get(question.id()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, QuestionType> types = new HashMap<>();
            entry.getValue().forEach(question -> types.put(question.id(), question.type()));
            AssessmentScore score = scoreEngine.scoreAttempts(skillAttempts, types);
            boolean passed = passPolicy.diagnosticPassed(score, skillAttempts.size());
            progress.recordDiagnosticResult(journeyId, entry.getKey(), score, passed);
            result.add(new DiagnosticSkillResult(entry.getKey(), score, passed, skillAttempts.size()));
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
                    JsonBaseModel.toJsonString(evaluation), current.selectedOptionIdsJson());
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
                question.id(), attemptId, JsonBaseModel.toJsonString(answer),
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
     * @param skillResults 诊断评估按技能拆分的结果；技能评估时为空列表
     * @param questionAttempts 本次提交的逐题结果
     */
    public record AssessmentSubmission(
            Assessment assessment,
            AssessmentAttempt attempt,
            AssessmentScore score,
            boolean passed,
            List<DiagnosticSkillResult> skillResults,
            List<QuestionAttempt> questionAttempts) {
    }

    /**
     * Diagnostic 对单个技能的评分结果。
     *
     * @param skillCode 技能编码
     * @param score 该技能题目的分数摘要
     * @param passed 是否达到诊断自动通过条件
     * @param evidenceCount 参与该技能判定的有效题目数量
     */
    public record DiagnosticSkillResult(
            String skillCode,
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
