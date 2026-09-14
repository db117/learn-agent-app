package com.example.agent.learning.assessment;

import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;

/** 面向 HTTP 的 Assessment 门面；各题型的业务处理位于三个工作流模块。 */
@Service
public class AssessmentService {

    private final AssessmentWorkflowSupport support;
    private final DiagnosticAssessmentWorkflow diagnostic;
    private final LearnUnitAssessmentWorkflow learnUnit;
    private final ChapterSynthesisAssessmentWorkflow synthesis;
    private final AssessmentResultReader results;

    public AssessmentService(
            LearningRepository repository,
            ProgressService progress,
            AssessmentScoreEngine scoreEngine,
            LearnUnitPassPolicy passPolicy,
            CodingAnswerEvaluator codingEvaluator,
            @Qualifier("llmDiagnosticQuestionPlanner") DiagnosticQuestionPlanner llmPlanner) {
        this.support = new AssessmentWorkflowSupport(repository, scoreEngine, passPolicy, codingEvaluator);
        AssessmentQuestionSet questionSet = new AssessmentQuestionSet(repository);
        this.diagnostic = new DiagnosticAssessmentWorkflow(support, questionSet, repository, progress, llmPlanner);
        this.learnUnit = new LearnUnitAssessmentWorkflow(support, questionSet, repository, progress);
        this.synthesis = new ChapterSynthesisAssessmentWorkflow(support, questionSet, repository, progress);
        this.results = new AssessmentResultReader(support, repository, scoreEngine, passPolicy);
    }

    @Transactional
    public AssessmentState createDiagnostic(String journeyId) {
        return diagnostic.create(journeyId);
    }

    @Transactional
    public AssessmentState createDiagnostic(
            String journeyId,
            Consumer<String> onModelText,
            Consumer<DiagnosticGenerationProgress> onProgress) {
        return diagnostic.create(journeyId, onModelText, onProgress);
    }

    public boolean requiresDiagnosticGeneration(String journeyId) {
        return diagnostic.requiresGeneration(journeyId);
    }

    public boolean requiresCodingEvaluation(String assessmentId) {
        return support.requiresCodingEvaluation(assessmentId);
    }

    @Transactional
    public AssessmentState createLearnUnitAssessment(String journeyId, String learnUnitCode) {
        return learnUnit.create(journeyId, learnUnitCode);
    }

    @Transactional
    public AssessmentState createLearnUnitPracticeAssessment(String journeyId, String learnUnitCode) {
        return learnUnit.createPractice(journeyId, learnUnitCode);
    }

    @Transactional
    public AssessmentState createChapterSynthesis(String journeyId, String chapterCode) {
        return synthesis.create(journeyId, chapterCode);
    }

    @Transactional
    public AssessmentState start(String assessmentId) {
        Assessment assessment = support.requireAssessment(assessmentId);
        return switch (assessment.type()) {
            case DIAGNOSTIC -> diagnostic.start(assessment);
            case LEARN_UNIT -> learnUnit.start(assessment);
            case CHAPTER_SYNTHESIS -> synthesis.start(assessment);
        };
    }

    @Transactional
    public AssessmentState retry(String journeyId, String learnUnitCode) {
        return learnUnit.retry(journeyId, learnUnitCode);
    }

    @Transactional
    public AssessmentState retryChapterSynthesis(String journeyId, String chapterCode) {
        return synthesis.retry(journeyId, chapterCode);
    }

    @Transactional
    public AssessmentState answer(String assessmentId, QuestionAnswer answer) {
        return toState(support.answer(assessmentId, answer));
    }

    @Transactional(noRollbackFor = AssessmentEvaluationException.class)
    public AssessmentSubmission submit(String assessmentId) {
        return submit(assessmentId, ignored -> {
        }, ignored -> {
        });
    }

    @Transactional(noRollbackFor = AssessmentEvaluationException.class)
    public AssessmentSubmission submit(String assessmentId, Consumer<String> onProgress) {
        return submit(assessmentId, onProgress, ignored -> {
        });
    }

    @Transactional(noRollbackFor = AssessmentEvaluationException.class)
    public AssessmentSubmission submit(
            String assessmentId, Consumer<String> onProgress, Consumer<String> onModelText) {
        Assessment assessment = support.requireAssessment(assessmentId);
        return switch (assessment.type()) {
            case DIAGNOSTIC -> diagnostic.submit(assessmentId, onProgress, onModelText);
            case LEARN_UNIT -> learnUnit.submit(assessmentId, onProgress, onModelText);
            case CHAPTER_SYNTHESIS -> synthesis.submit(assessmentId, onProgress, onModelText);
        };
    }

    /** 只读结果路径；不会调用 ProgressService，也不会改变 Attempt 状态。 */
    @Transactional(readOnly = true)
    public AssessmentSubmission completedResult(String assessmentId) {
        return results.completedResult(assessmentId);
    }

    /** 重新组装固定题集、当前 Attempt 和历史 Attempt。 */
    public AssessmentState state(Assessment assessment) {
        return results.state(assessment);
    }

    private AssessmentState toState(AssessmentWorkflowSupport.AssessmentStateHolder state) {
        return new AssessmentState(
                state.assessment(), state.questions(), state.openAttempt(), state.attempts(), state.questionAttempts());
    }

    public record DiagnosticGenerationProgress(String stage, List<Question> questions) {
        public DiagnosticGenerationProgress {
            questions = List.copyOf(questions == null ? List.of() : questions);
        }
    }

    /** Assessment 当前状态，供 API 和前端恢复进行中的答案。 */
    public record AssessmentState(
            Assessment assessment,
            List<Question> questions,
            AssessmentAttempt openAttempt,
            List<AssessmentAttempt> attempts,
            List<QuestionAttempt> questionAttempts) {
    }

    /** Assessment 提交后的结果。 */
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
    }

    /** Diagnostic 对单个 LearnUnit 的评分结果。 */
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
