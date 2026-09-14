package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;

import java.util.List;
import java.util.function.Consumer;

/** LearnUnit Assessment module: independent checks, retries and mastery updates. */
final class LearnUnitAssessmentWorkflow {

    private final AssessmentWorkflowSupport support;
    private final AssessmentQuestionSet questionSet;
    private final LearningRepository repository;
    private final ProgressService progress;

    LearnUnitAssessmentWorkflow(
            AssessmentWorkflowSupport support,
            AssessmentQuestionSet questionSet,
            LearningRepository repository,
            ProgressService progress) {
        this.support = support;
        this.questionSet = questionSet;
        this.repository = repository;
        this.progress = progress;
    }

    AssessmentService.AssessmentState create(String journeyId, String learnUnitCode) {
        progress.requireCurrentLearnUnit(journeyId, learnUnitCode);
        return createInternal(journeyId, learnUnitCode);
    }

    AssessmentService.AssessmentState createPractice(String journeyId, String learnUnitCode) {
        progress.requireCompletedLearnUnit(journeyId, learnUnitCode);
        return createInternal(journeyId, learnUnitCode);
    }

    AssessmentService.AssessmentState start(Assessment assessment) {
        AssessmentWorkflowSupport.StartResult started = support.start(assessment);
        if (started.created()) {
            var pathItem = progress.pathItem(assessment.journeyId(), assessment.learnUnitCode());
            if (pathItem.status() == com.example.agent.learning.path.LearningPathItemStatus.COMPLETED) {
                progress.requireCompletedLearnUnit(assessment.journeyId(), assessment.learnUnitCode());
            } else {
                progress.markAssessing(assessment.journeyId(), assessment.learnUnitCode());
            }
        }
        return toState(started.state());
    }

    AssessmentService.AssessmentState retry(String journeyId, String learnUnitCode) {
        var pathItem = progress.pathItem(journeyId, learnUnitCode);
        if (pathItem.status() == com.example.agent.learning.path.LearningPathItemStatus.COMPLETED) {
            progress.requireCompletedLearnUnit(journeyId, learnUnitCode);
        } else {
            progress.requireCurrentLearnUnit(journeyId, learnUnitCode);
        }
        Assessment assessment = repository.findLatestLearnUnitAssessment(journeyId, learnUnitCode)
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit has no assessment to retry: " + learnUnitCode));
        AssessmentService.AssessmentState current = toState(support.state(assessment));
        if (current.openAttempt() != null) return current;
        AssessmentAttempt latest = current.attempts().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit has no completed attempt to retry: " + learnUnitCode));
        if (latest.completedAt() == null || !Boolean.FALSE.equals(latest.passed())) {
            throw new IllegalArgumentException("LearnUnit assessment is not retryable: " + learnUnitCode);
        }
        return start(support.requireAssessment(assessment.id()));
    }

    AssessmentService.AssessmentSubmission submit(
            String assessmentId, Consumer<String> onProgress, Consumer<String> onModelText) {
        AssessmentWorkflowSupport.PreparedSubmission prepared;
        try {
            prepared = support.prepareSubmission(assessmentId, onProgress, onModelText);
        } catch (AssessmentService.AssessmentEvaluationException error) {
            Assessment failed = support.requireAssessment(assessmentId);
            progress.markAssessmentFailed(failed.journeyId(), failed.learnUnitCode());
            throw error;
        }
        LearnUnit learnUnit = repository.findLearnUnit(prepared.assessment().learnUnitCode()).orElseThrow();
        AssessmentScore score = prepared.score();
        boolean passed = support.passPolicy().passed(score, learnUnit);
        progress.recordLearnUnitAssessment(
                prepared.assessment().journeyId(), prepared.assessment().learnUnitCode(), score, passed);
        return support.complete(prepared, new AssessmentWorkflowSupport.Completion(
                passed, List.of(), learnUnit.passScore(), score.hasCodingQuestions() ? learnUnit.minCodingScore() : null,
                null, false));
    }

    private AssessmentService.AssessmentState createInternal(String journeyId, String learnUnitCode) {
        support.requireJourney(journeyId);
        LearnUnit learnUnit = repository.listLearnUnitsForJourney(journeyId).stream()
                .filter(candidate -> candidate.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("learnUnit does not belong to journey: " + learnUnitCode));
        return repository.findLatestLearnUnitAssessment(journeyId, learnUnitCode)
                .map(assessment -> toState(support.state(assessment)))
                .orElseGet(() -> {
                    List<Question> available = repository.listQuestionsForLearnUnit(learnUnitCode).stream()
                            .filter(question -> question.role() == QuestionRole.INDEPENDENT)
                            .toList();
                    if (available.isEmpty()) {
                        throw new IllegalStateException("learnUnit has no persisted independent questions: " + learnUnitCode);
                    }
                    List<Question> questions = questionSet.normalize(available, List.of(learnUnit), available, 1);
                    return toState(support.createFixed(
                            journeyId, learnUnitCode, null, AssessmentType.LEARN_UNIT, questions));
                });
    }

    private AssessmentService.AssessmentState toState(AssessmentWorkflowSupport.AssessmentStateHolder state) {
        return new AssessmentService.AssessmentState(
                state.assessment(), state.questions(), state.openAttempt(), state.attempts(), state.questionAttempts());
    }
}
