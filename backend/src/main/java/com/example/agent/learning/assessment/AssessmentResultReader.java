package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only Assessment state and completed-result assembly. */
final class AssessmentResultReader {

    private final AssessmentWorkflowSupport support;
    private final LearningRepository repository;
    private final AssessmentScoreEngine scoreEngine;
    private final LearnUnitPassPolicy passPolicy;

    AssessmentResultReader(
            AssessmentWorkflowSupport support,
            LearningRepository repository,
            AssessmentScoreEngine scoreEngine,
            LearnUnitPassPolicy passPolicy) {
        this.support = support;
        this.repository = repository;
        this.scoreEngine = scoreEngine;
        this.passPolicy = passPolicy;
    }

    AssessmentService.AssessmentState state(Assessment assessment) {
        AssessmentWorkflowSupport.AssessmentStateHolder state = support.state(assessment);
        return new AssessmentService.AssessmentState(
                state.assessment(), state.questions(), state.openAttempt(), state.attempts(), state.questionAttempts());
    }

    AssessmentService.AssessmentSubmission completedResult(String assessmentId) {
        Assessment assessment = support.requireAssessment(assessmentId);
        if (assessment.status() != AssessmentStatus.COMPLETED) {
            throw new IllegalStateException("assessment is not completed");
        }
        AssessmentAttempt attempt = repository.listAttemptsForAssessment(assessmentId).stream()
                .filter(value -> value.completedAt() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("completed assessment has no completed attempt"));
        List<Question> questions = repository.listQuestionsForAssessment(assessmentId);
        List<QuestionAttempt> questionAttempts = repository.listQuestionAttempts(attempt.id());
        AssessmentScore score = scoreEngine.scoreAttempts(questionAttempts, questionTypes(questions));
        LearnUnit learnUnit = assessment.type() == AssessmentType.LEARN_UNIT
                ? repository.findLearnUnit(assessment.learnUnitCode()).orElseThrow() : null;
        int passScore = switch (assessment.type()) {
            case DIAGNOSTIC -> LearnUnitPassPolicy.DIAGNOSTIC_PASS_SCORE;
            case CHAPTER_SYNTHESIS -> LearnUnitPassPolicy.SYNTHESIS_PASS_SCORE;
            case LEARN_UNIT -> learnUnit.passScore();
        };
        Integer codingPassScore = assessment.type() == AssessmentType.LEARN_UNIT && score.hasCodingQuestions()
                ? learnUnit.minCodingScore() : null;
        List<AssessmentService.DiagnosticLearnUnitResult> diagnosticResults = assessment.type() == AssessmentType.DIAGNOSTIC
                ? diagnosticResults(questions, questionAttempts) : List.of();
        String reviewLearnUnitCode = null;
        boolean chapterCompleted = false;
        if (assessment.type() == AssessmentType.CHAPTER_SYNTHESIS) {
            ChapterResult chapter = chapterResult(assessment, Boolean.TRUE.equals(attempt.passed()));
            reviewLearnUnitCode = chapter.reviewLearnUnitCode();
            chapterCompleted = chapter.completed();
        }
        return new AssessmentService.AssessmentSubmission(
                assessment, attempt, score, Boolean.TRUE.equals(attempt.passed()), diagnosticResults,
                questionAttempts, passScore, codingPassScore, reviewLearnUnitCode, chapterCompleted);
    }

    private List<AssessmentService.DiagnosticLearnUnitResult> diagnosticResults(
            List<Question> questions, List<QuestionAttempt> attempts) {
        Map<String, List<Question>> questionsByLearnUnit = new LinkedHashMap<>();
        for (Question question : questions) {
            questionsByLearnUnit.computeIfAbsent(question.learnUnitCode(), ignored -> new ArrayList<>()).add(question);
        }
        Map<String, QuestionAttempt> attemptsByQuestion = new HashMap<>();
        attempts.forEach(attempt -> attemptsByQuestion.put(attempt.questionId(), attempt));
        List<AssessmentService.DiagnosticLearnUnitResult> result = new ArrayList<>();
        for (Map.Entry<String, List<Question>> entry : questionsByLearnUnit.entrySet()) {
            List<QuestionAttempt> learnUnitAttempts = entry.getValue().stream()
                    .map(question -> attemptsByQuestion.get(question.id()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            AssessmentScore score = scoreEngine.scoreAttempts(learnUnitAttempts, questionTypes(entry.getValue()));
            result.add(new AssessmentService.DiagnosticLearnUnitResult(
                    entry.getKey(), score, passPolicy.diagnosticPassed(score, learnUnitAttempts.size()),
                    learnUnitAttempts.size()));
        }
        return result;
    }

    private Map<String, QuestionType> questionTypes(List<Question> questions) {
        Map<String, QuestionType> types = new HashMap<>();
        questions.forEach(question -> types.put(question.id(), question.type()));
        return types;
    }

    private ChapterResult chapterResult(Assessment assessment, boolean passed) {
        List<LearnUnit> units = repository.listLearnUnitsForJourney(assessment.journeyId()).stream()
                .filter(unit -> assessment.chapterCode().equals(unit.chapterCode()))
                .toList();
        var codes = units.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
        List<LearningPathItem> chapterPath = repository.listPath(assessment.journeyId()).stream()
                .filter(item -> codes.contains(item.learnUnitCode()))
                .sorted(java.util.Comparator.comparingInt(LearningPathItem::sequence)
                        .thenComparing(LearningPathItem::learnUnitCode))
                .toList();
        String firstWeak = chapterPath.stream()
                .filter(item -> passed
                        ? item.status() != LearningPathItemStatus.COMPLETED || item.needsReview()
                        : item.needsReview())
                .map(LearningPathItem::learnUnitCode)
                .findFirst().orElse(null);
        boolean completed = passed && chapterPath.size() == units.size()
                && chapterPath.stream().allMatch(item ->
                item.status() == LearningPathItemStatus.COMPLETED && !item.needsReview());
        return new ChapterResult(firstWeak, completed);
    }

    private record ChapterResult(String reviewLearnUnitCode, boolean completed) {
    }
}
