package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Diagnostic Assessment 模块：负责固定题集规划和 Path 初始化。 */
final class DiagnosticAssessmentWorkflow {

    private static final int MIN_EVIDENCE = 2;

    private final AssessmentWorkflowSupport support;
    private final AssessmentQuestionSet questionSet;
    private final LearningRepository repository;
    private final ProgressService progress;
    private final DiagnosticQuestionPlanner llmPlanner;

    DiagnosticAssessmentWorkflow(
            AssessmentWorkflowSupport support,
            AssessmentQuestionSet questionSet,
            LearningRepository repository,
            ProgressService progress,
            DiagnosticQuestionPlanner llmPlanner) {
        this.support = support;
        this.questionSet = questionSet;
        this.repository = repository;
        this.progress = progress;
        this.llmPlanner = llmPlanner;
    }

    AssessmentService.AssessmentState create(String journeyId) {
        return create(journeyId, null, ignored -> {
        });
    }

    AssessmentService.AssessmentState create(
            String journeyId,
            Consumer<String> onModelText,
            Consumer<AssessmentService.DiagnosticGenerationProgress> onProgress) {
        Consumer<AssessmentService.DiagnosticGenerationProgress> progressCallback =
                onProgress == null ? ignored -> {
                } : onProgress;
        return repository.findDiagnosticAssessment(journeyId)
                .map(assessment -> toState(support.state(assessment)))
                .orElseGet(() -> {
                    DiagnosticInput input = diagnosticInput(journeyId);
                    List<Question> selected = questionSet.hasDiagnosticCoverage(
                            input.available(), input.learnUnits(), MIN_EVIDENCE)
                            ? questionSet.normalize(input.available(), input.learnUnits(), input.available(), MIN_EVIDENCE)
                            : planQuestions(input.language(), input.learnUnits(), input.available(), input.profile(),
                            MIN_EVIDENCE, onModelText);
                    if (selected.isEmpty()) throw new IllegalStateException("No diagnostic questions are available");
                    progressCallback.accept(new AssessmentService.DiagnosticGenerationProgress("VALIDATING", selected));
                    progressCallback.accept(new AssessmentService.DiagnosticGenerationProgress("PERSISTING", selected));
                    questionSet.insertNewQuestions(selected, input.available());
                    return toState(support.createFixed(
                            journeyId, null, null, AssessmentType.DIAGNOSTIC, selected));
                });
    }

    boolean requiresGeneration(String journeyId) {
        if (repository.findDiagnosticAssessment(journeyId).isPresent()) return false;
        DiagnosticInput input = diagnosticInput(journeyId);
        return !questionSet.hasDiagnosticCoverage(input.available(), input.learnUnits(), MIN_EVIDENCE);
    }

    AssessmentService.AssessmentState start(Assessment assessment) {
        if (assessment.status() == AssessmentStatus.COMPLETED) {
            throw new IllegalArgumentException("diagnostic is already completed");
        }
        return toState(support.start(assessment).state());
    }

    AssessmentService.AssessmentSubmission submit(
            String assessmentId, Consumer<String> onProgress, Consumer<String> onModelText) {
        AssessmentWorkflowSupport.PreparedSubmission prepared = support.prepareSubmission(
                assessmentId, onProgress, onModelText);
        List<AssessmentService.DiagnosticLearnUnitResult> results = diagnosticResults(
                prepared.assessment().journeyId(), prepared.questions(), prepared.questionAttempts());
        boolean passed = results.stream().allMatch(AssessmentService.DiagnosticLearnUnitResult::passed);
        progress.generatePath(prepared.assessment().journeyId());
        return support.complete(prepared, new AssessmentWorkflowSupport.Completion(
                passed, results, LearnUnitPassPolicy.DIAGNOSTIC_PASS_SCORE, null, null, false));
    }

    private List<AssessmentService.DiagnosticLearnUnitResult> diagnosticResults(
            String journeyId, List<Question> questions, List<QuestionAttempt> attempts) {
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
            Map<String, QuestionType> types = new HashMap<>();
            entry.getValue().forEach(question -> types.put(question.id(), question.type()));
            AssessmentScore score = support.scoreEngine().scoreAttempts(learnUnitAttempts, types);
            boolean passed = support.passPolicy().diagnosticPassed(score, learnUnitAttempts.size());
            progress.recordDiagnosticResult(journeyId, entry.getKey(), score, passed);
            result.add(new AssessmentService.DiagnosticLearnUnitResult(
                    entry.getKey(), score, passed, learnUnitAttempts.size()));
        }
        return result;
    }

    private List<Question> planQuestions(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> available,
            LearnerProfile profile,
            int minimumEvidence,
            Consumer<String> onModelText) {
        try {
            List<Question> planned = onModelText == null
                    ? llmPlanner.plan(language, learnUnits, available, profile)
                    : llmPlanner.plan(language, learnUnits, available, profile, onModelText);
            return questionSet.normalize(planned, learnUnits, available, minimumEvidence);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to generate assessment questions", error);
        }
    }

    private DiagnosticInput diagnosticInput(String journeyId) {
        var journey = support.requireJourney(journeyId);
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
        return new DiagnosticInput(language, learnUnits, available, profile);
    }

    private AssessmentService.AssessmentState toState(AssessmentWorkflowSupport.AssessmentStateHolder state) {
        return new AssessmentService.AssessmentState(
                state.assessment(), state.questions(), state.openAttempt(), state.attempts(), state.questionAttempts());
    }

    private record DiagnosticInput(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> available,
            LearnerProfile profile) {
    }
}
