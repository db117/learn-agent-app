package com.example.agent.learning.assessment;

import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Assessment 工作流共用的固定题集、Attempt 和答案处理机制。 */
final class AssessmentWorkflowSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MultipleChoiceEvaluator MULTIPLE_CHOICE = new MultipleChoiceEvaluator();

    private final LearningRepository repository;
    private final AssessmentScoreEngine scoreEngine;
    private final LearnUnitPassPolicy passPolicy;
    private final CodingAnswerEvaluator codingEvaluator;

    AssessmentWorkflowSupport(
            LearningRepository repository,
            AssessmentScoreEngine scoreEngine,
            LearnUnitPassPolicy passPolicy,
            CodingAnswerEvaluator codingEvaluator) {
        this.repository = repository;
        this.scoreEngine = scoreEngine;
        this.passPolicy = passPolicy;
        this.codingEvaluator = codingEvaluator;
    }

    AssessmentScoreEngine scoreEngine() {
        return scoreEngine;
    }

    LearnUnitPassPolicy passPolicy() {
        return passPolicy;
    }

    Assessment requireAssessment(String id) {
        return repository.findAssessment(id)
                .orElseThrow(() -> new IllegalArgumentException("assessment not found: " + id));
    }

    com.example.agent.learning.journey.LearningJourney requireJourney(String id) {
        return repository.findJourney(id)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + id));
    }

    AssessmentStateHolder state(Assessment assessment) {
        var openAttempt = repository.findOpenAttempt(assessment.id());
        return new AssessmentStateHolder(
                assessment,
                repository.listQuestionsForAssessment(assessment.id()),
                openAttempt.orElse(null),
                repository.listAttemptsForAssessment(assessment.id()),
                openAttempt.map(attempt -> repository.listQuestionAttempts(attempt.id())).orElse(List.of()));
    }

    AssessmentStateHolder createFixed(
            String journeyId, String learnUnitCode, String chapterCode, AssessmentType type, List<Question> questions) {
        Assessment assessment = new Assessment(
                UUID.randomUUID().toString(), journeyId, learnUnitCode, chapterCode, type,
                AssessmentStatus.CREATED, Instant.now(), null);
        repository.insertAssessment(assessment);
        for (int index = 0; index < questions.size(); index++) {
            repository.insertAssessmentQuestion(assessment.id(), questions.get(index).id(), index);
        }
        return state(assessment);
    }

    StartResult start(Assessment assessment) {
        var open = repository.findOpenAttempt(assessment.id());
        if (open.isPresent()) return new StartResult(state(assessment), false);
        int number = repository.nextAttemptNumber(assessment.id());
        Instant now = Instant.now();
        repository.insertAttempt(new AssessmentAttempt(
                UUID.randomUUID().toString(), assessment.id(), assessment.journeyId(), assessment.learnUnitCode(),
                number, null, null, null, null, now, null));
        repository.updateAssessment(assessment.id(), AssessmentStatus.IN_PROGRESS, null);
        return new StartResult(state(requireAssessment(assessment.id())), true);
    }

    AssessmentStateHolder answer(String assessmentId, QuestionAnswer answer) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.status() != AssessmentStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("assessment is not in progress");
        }
        AssessmentAttempt attempt = openAttempt(assessmentId);
        Question question = question(assessmentId, answer.questionId());
        QuestionAttempt result;
        if (question.type() == QuestionType.MULTIPLE_CHOICE) {
            MultipleChoiceEvaluator.Result evaluated = MULTIPLE_CHOICE.evaluate(question, answer.selectedOptionIds());
            result = new QuestionAttempt(
                    question.id(), attempt.id(), json(answer), evaluated.score(), question.points(),
                    evaluated.correct() ? "Correct." : "Review this concept and try again.", evaluated.correct(),
                    null, null, answer.selectedOptionIds());
        } else {
            result = new QuestionAttempt(
                    question.id(), attempt.id(), json(answer), null, question.points(), null, null,
                    answer.submittedCode(), null, null);
        }
        repository.saveQuestionAttempt(result);
        return state(assessment);
    }

    boolean requiresCodingEvaluation(String assessmentId) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.status() != AssessmentStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("assessment is not in progress");
        }
        AssessmentAttempt attempt = openAttempt(assessmentId);
        Map<String, QuestionAttempt> attempts = new HashMap<>();
        repository.listQuestionAttempts(attempt.id()).forEach(value -> attempts.put(value.questionId(), value));
        return repository.listQuestionsForAssessment(assessmentId).stream()
                .filter(question -> question.type() == QuestionType.CODING)
                .anyMatch(question -> {
                    QuestionAttempt current = attempts.get(question.id());
                    return current == null || current.score() == null
                            || current.evaluationJson() == null || current.evaluationJson().isBlank();
                });
    }

    PreparedSubmission prepareSubmission(
            String assessmentId, Consumer<String> onProgress, Consumer<String> onModelText) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.status() != AssessmentStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("assessment is not in progress");
        }
        AssessmentAttempt attempt = openAttempt(assessmentId);
        List<Question> questions = repository.listQuestionsForAssessment(assessmentId);
        Map<String, QuestionAttempt> attempts = new LinkedHashMap<>();
        repository.listQuestionAttempts(attempt.id()).forEach(value -> attempts.put(value.questionId(), value));
        Consumer<String> progressCallback = onProgress == null ? ignored -> {
        } : onProgress;
        for (Question question : questions) {
            QuestionAttempt current = attempts.get(question.id());
            if (current == null) {
                current = emptyAttempt(question, attempt.id());
                repository.saveQuestionAttempt(current);
            }
            if (question.type() == QuestionType.CODING
                    && (current.score() == null || current.evaluationJson() == null || current.evaluationJson().isBlank())) {
                progressCallback.accept("ANALYZING");
                current = evaluateCoding(question, current, progressCallback, onModelText);
                repository.saveQuestionAttempt(current);
            }
            attempts.put(question.id(), current);
        }
        List<QuestionAttempt> completedQuestions = questions.stream().map(question -> attempts.get(question.id())).toList();
        Map<String, QuestionType> types = new HashMap<>();
        questions.forEach(question -> types.put(question.id(), question.type()));
        return new PreparedSubmission(assessment, attempt, questions, completedQuestions,
                scoreEngine.scoreAttempts(completedQuestions, types));
    }

    AssessmentService.AssessmentSubmission complete(PreparedSubmission prepared, Completion completion) {
        Instant completedAt = Instant.now();
        repository.updateAttempt(
                prepared.attempt().id(), prepared.score().choiceScore(), prepared.score().codingScore(),
                prepared.score().totalScore(), completion.passed(), completedAt);
        repository.updateAssessment(prepared.assessment().id(), AssessmentStatus.COMPLETED, completedAt);
        return new AssessmentService.AssessmentSubmission(
                requireAssessment(prepared.assessment().id()),
                repository.findAttempt(prepared.attempt().id()).orElseThrow(),
                prepared.score(), completion.passed(), completion.learnUnitResults(), prepared.questionAttempts(),
                completion.passScore(), completion.codingPassScore(), completion.reviewLearnUnitCode(),
                completion.chapterCompleted());
    }

    String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize assessment data", error);
        }
    }

    private QuestionAttempt evaluateCoding(
            Question question, QuestionAttempt current, Consumer<String> onProgress, Consumer<String> onModelText) {
        try {
            CodingEvaluationResult evaluation = codingEvaluator.evaluate(
                    CodingQuestion.from(question), current.submittedCode(), onModelText);
            onProgress.accept("VALIDATING");
            int score = (int) Math.round(evaluation.totalScore() * question.points() / 100.0);
            onProgress.accept("PERSISTING");
            return new QuestionAttempt(
                    current.questionId(), current.assessmentAttemptId(), current.answerJson(), score,
                    current.maxScore(), evaluation.feedback(), null, current.submittedCode(),
                    json(evaluation), current.selectedOptionIds());
        } catch (RuntimeException error) {
            QuestionAttempt draft = new QuestionAttempt(
                    current.questionId(), current.assessmentAttemptId(), current.answerJson(), null, current.maxScore(),
                    "评分暂不可用，请保留答案后重试。", null, current.submittedCode(), null,
                    current.selectedOptionIds());
            repository.saveQuestionAttempt(draft);
            throw new AssessmentService.AssessmentEvaluationException(
                    "Coding evaluation failed; draft was preserved", error);
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
                question.type() == QuestionType.MULTIPLE_CHOICE ? List.of() : List.of());
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

    record AssessmentStateHolder(
            Assessment assessment,
            List<Question> questions,
            AssessmentAttempt openAttempt,
            List<AssessmentAttempt> attempts,
            List<QuestionAttempt> questionAttempts) {
    }

    record StartResult(AssessmentStateHolder state, boolean created) {
    }

    record PreparedSubmission(
            Assessment assessment,
            AssessmentAttempt attempt,
            List<Question> questions,
            List<QuestionAttempt> questionAttempts,
            AssessmentScore score) {
    }

    record Completion(
            boolean passed,
            List<AssessmentService.DiagnosticLearnUnitResult> learnUnitResults,
            int passScore,
            Integer codingPassScore,
            String reviewLearnUnitCode,
            boolean chapterCompleted) {
    }
}
