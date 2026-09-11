import type {AssessmentResultResponse} from "../lib/api";

type ResultViewProps = {
    assessmentResult: AssessmentResultResponse | null;
    learnUnitLabel: (code: string) => string;
    busy: boolean;
    canNext: boolean;
    canRetry: boolean;
    onNext: () => void | Promise<void>;
    onContinue: () => void | Promise<void>;
    onRetry: () => void | Promise<void>;
    onOpenReview: (code: string) => void | Promise<void>;
};

export function ResultView({
                               assessmentResult,
                               learnUnitLabel,
                               busy,
                               canNext,
                               canRetry,
                               onNext,
                               onContinue,
                               onRetry,
                               onOpenReview,
                           }: ResultViewProps) {
    if (!assessmentResult) return null;
    const diagnostic = assessmentResult.assessment.type === "DIAGNOSTIC";
    const synthesis = assessmentResult.assessment.type === "CHAPTER_SYNTHESIS";
    const resultStatus = assessmentResult.passed
        ? synthesis && !assessmentResult.chapterCompleted ? "Synthesis passed · Chapter unresolved" : "Passed"
        : diagnostic ? "Not Yet" : "Retry Required";
    const feedbackAttempts = assessmentResult.questionAttempts.filter((item) => item.feedback?.trim());

    return (
        <section className="result panel">
            <div className="section-kicker">ASSESSMENT COMPLETE</div>
            <h2>{diagnostic ? "你的学习路径已经准备好了" : synthesis ? "Chapter synthesis 完成" : "LearnUnit 评估完成"}</h2>
            <div className="score-summary">
                <strong>{assessmentResult.score.totalScore}</strong><span>/ 100</span>
                <p className={assessmentResult.passed ? "success" : "warning"}>{resultStatus}</p>
            </div>
            {!diagnostic && !assessmentResult.passed && <p className="warning" role="status">
                {synthesis
                    ? "本次 Chapter synthesis 未通过。请回到服务端选出的薄弱 LearnUnit 做 remediation，再 Retry。"
                    : "本次独立检查未通过。Review 当前能力后可用相同题集 Retry；这次结果不会记为掌握。"}
            </p>}
            {synthesis && assessmentResult.passed && !assessmentResult.chapterCompleted && <p className="warning" role="status">
                Synthesis 达标，但 Chapter 仍有 skipped、未通过或 review debt 的 LearnUnit，不能宣称 Chapter 已完成。
            </p>}
            <div className="score-breakdown">
                {assessmentResult.score.hasChoiceQuestions && <span>选择题 {assessmentResult.score.choiceScore}</span>}
                {assessmentResult.score.hasCodingQuestions && <span>Coding {assessmentResult.score.codingScore}</span>}
            </div>
            <div className="score-thresholds">
                <span>通过阈值 {assessmentResult.passScore}</span>
                <span>{assessmentResult.codingPassScore === null ? "编码阈值不适用" : `编码阈值 ${assessmentResult.codingPassScore}`}</span>
            </div>
            {feedbackAttempts.length > 0 && (
                <div className="feedback-block">
                    <h3>逐题反馈</h3>
                    {feedbackAttempts.map((item) => (
                        <p key={`${item.assessmentAttemptId}-${item.questionId}`}>
                            {item.feedback} <span className="muted">({item.score ?? 0}/{item.maxScore})</span>
                        </p>
                    ))}
                </div>
            )}
            {assessmentResult.learnUnitResults.length > 0 && (
                <div className="result-list">
                    {assessmentResult.learnUnitResults.map((item) => (
                        <div className="result-row" key={item.learnUnitCode}>
                            <span>{learnUnitLabel(item.learnUnitCode)}</span>
                            <span>{item.score.totalScore} · {item.passed ? "已掌握" : "进入路径"}</span>
                        </div>
                    ))}
                </div>
            )}
            <div className="button-row result-actions">
                <button className="primary" onClick={() => void (canNext ? onNext() : onContinue())} disabled={busy}>
                    {busy ? "加载路径…" : canNext ? "Next LearnUnit" : synthesis ? "返回 Chapter" : "Continue"}
                </button>
                {!diagnostic && !assessmentResult.passed && (
                    <button className="secondary" onClick={() => void onRetry()}
                            disabled={busy || !canRetry}>Retry</button>
                )}
                {synthesis && assessmentResult.reviewLearnUnitCode && <button className="secondary"
                                                                            onClick={() => void onOpenReview(assessmentResult.reviewLearnUnitCode!)}
                                                                            disabled={busy}>
                    查看薄弱 LearnUnit
                </button>}
            </div>
        </section>
    );
}
