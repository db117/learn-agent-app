package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Chapter synthesis Assessment module: chapter-wide fixed questions and review outcome. */
final class ChapterSynthesisAssessmentWorkflow {

    private final AssessmentWorkflowSupport support;
    private final AssessmentQuestionSet questionSet;
    private final LearningRepository repository;
    private final ProgressService progress;

    ChapterSynthesisAssessmentWorkflow(
            AssessmentWorkflowSupport support,
            AssessmentQuestionSet questionSet,
            LearningRepository repository,
            ProgressService progress) {
        this.support = support;
        this.questionSet = questionSet;
        this.repository = repository;
        this.progress = progress;
    }

    AssessmentService.AssessmentState create(String journeyId, String chapterCode) {
        return repository.findLatestChapterSynthesisAssessment(journeyId, chapterCode)
                .map(assessment -> toState(support.state(assessment)))
                .orElseGet(() -> {
                    support.requireJourney(journeyId);
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
                            ? synthesisQuestions(chapter, learnUnits)
                            : questionSet.validateSynthesisQuestions(available, chapterCode);
                    questionSet.insertNewQuestions(selected, available);
                    return toState(support.createFixed(
                            journeyId, null, chapterCode, AssessmentType.CHAPTER_SYNTHESIS, selected));
                });
    }

    AssessmentService.AssessmentState start(Assessment assessment) {
        return toState(support.start(assessment).state());
    }

    AssessmentService.AssessmentState retry(String journeyId, String chapterCode) {
        Assessment assessment = repository.findLatestChapterSynthesisAssessment(journeyId, chapterCode)
                .orElseThrow(() -> new IllegalArgumentException("Chapter has no synthesis to retry: " + chapterCode));
        AssessmentService.AssessmentState current = toState(support.state(assessment));
        if (current.openAttempt() != null) return current;
        AssessmentAttempt latest = current.attempts().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Chapter synthesis has no completed attempt to retry: " + chapterCode));
        if (latest.completedAt() == null || !Boolean.FALSE.equals(latest.passed())) {
            throw new IllegalArgumentException("Chapter synthesis is not retryable: " + chapterCode);
        }
        return start(support.requireAssessment(assessment.id()));
    }

    AssessmentService.AssessmentSubmission submit(
            String assessmentId, Consumer<String> onProgress, Consumer<String> onModelText) {
        AssessmentWorkflowSupport.PreparedSubmission prepared = support.prepareSubmission(
                assessmentId, onProgress, onModelText);
        AssessmentScore score = prepared.score();
        boolean passed = support.passPolicy().synthesisPassed(score);
        List<LearnUnit> chapterUnits = repository.listLearnUnitsForJourney(prepared.assessment().journeyId()).stream()
                .filter(unit -> prepared.assessment().chapterCode().equals(unit.chapterCode()))
                .toList();
        ProgressService.ChapterSynthesisOutcome outcome = progress.recordChapterSynthesis(
                prepared.assessment().journeyId(), prepared.assessment().chapterCode(), score, passed,
                questionSet.coveredLearnUnitCodes(prepared.questions(), chapterUnits));
        return support.complete(prepared, new AssessmentWorkflowSupport.Completion(
                passed, List.of(), LearnUnitPassPolicy.SYNTHESIS_PASS_SCORE, null,
                outcome.firstWeakLearnUnitCode(), outcome.chapterCompleted()));
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
                    "generated-synthesis-question-" + java.util.UUID.randomUUID(), null, chapter.code(),
                    QuestionType.MULTIPLE_CHOICE, 1,
                    "围绕“" + learnUnit.name() + "”，哪项表现符合 Chapter 的综合目标？", 20,
                    support.json(config), null, null, null, support.json(List.of(learnUnit.code())), false,
                    QuestionRole.SYNTHESIS);
            QuestionStructureValidator.validate(question);
            result.add(question);
        }
        return result;
    }

    private AssessmentService.AssessmentState toState(AssessmentWorkflowSupport.AssessmentStateHolder state) {
        return new AssessmentService.AssessmentState(
                state.assessment(), state.questions(), state.openAttempt(), state.attempts(), state.questionAttempts());
    }
}
