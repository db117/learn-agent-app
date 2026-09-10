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
                           }: ResultViewProps) {
    if (!assessmentResult) return null;
    const diagnostic = assessmentResult.assessment.type === "DIAGNOSTIC";
    const resultStatus = assessmentResult.passed ? "Passed" : diagnostic ? "Not Yet" : "Retry Required";

    return (
        <section className="result panel">
            <div className="section-kicker">ASSESSMENT COMPLETE</div>
            <h2>{diagnostic ? "你的学习路径已经准备好了" : "LearnUnit 评估完成"}</h2>
            <div className="score-summary">
                <strong>{assessmentResult.score.totalScore}</strong><span>/ 100</span>
                <p className={assessmentResult.passed ? "success" : "warning"}>{resultStatus}</p>
            </div>
            <div className="score-breakdown">
                {assessmentResult.score.hasChoiceQuestions && <span>选择题 {assessmentResult.score.choiceScore}</span>}
                {assessmentResult.score.hasCodingQuestions && <span>Coding {assessmentResult.score.codingScore}</span>}
            </div>
            <div className="score-thresholds">
                <span>通过阈值 {assessmentResult.passScore}</span>
                <span>{assessmentResult.codingPassScore === null ? "编码阈值不适用" : `编码阈值 ${assessmentResult.codingPassScore}`}</span>
            </div>
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
                    {busy ? "加载路径…" : canNext ? "Next LearnUnit" : "Continue"}
                </button>
                {!diagnostic && !assessmentResult.passed && (
                    <button className="secondary" onClick={() => void onRetry()}
                            disabled={busy || !canRetry}>Retry</button>
                )}
            </div>
        </section>
    );
}
