import type {JourneyDetail, LearnUnit, LearnUnitResponse} from "../lib/api";
import {TutorPanel, type TutorPanelProps} from "./TutorPanel";

type DashboardViewProps = {
    journey: JourneyDetail | null;
    learnUnits: LearnUnit[];
    learnUnit: LearnUnitResponse | null;
    busy: boolean;
    currentLearnUnit: boolean;
    hasOpenAttempt: boolean;
    canRetry: boolean;
    learnUnitLabel: (learnUnits: LearnUnit[], code: string) => string;
    onOpenLearnUnit: (code: string) => void | Promise<void>;
    onRetryCurrentLearnUnit: () => void | Promise<void>;
    onStartLearnUnitAssessment: () => void | Promise<void>;
    onSkipCurrentLearnUnit: () => void | Promise<void>;
    tutor: TutorPanelProps;
};

function pathStatusLabel(status: string) {
    switch (status) {
        case "COMPLETED":
            return "Passed";
        case "SKIPPED":
            return "Skipped";
        case "CURRENT":
            return "Current";
        default:
            return "Pending";
    }
}

export function DashboardView({
                                  journey,
                                  learnUnits,
                                  learnUnit,
                                  busy,
                                  currentLearnUnit,
                                  hasOpenAttempt,
                                  canRetry,
                                  learnUnitLabel,
                                  onOpenLearnUnit,
                                  onRetryCurrentLearnUnit,
                                  onStartLearnUnitAssessment,
                                  onSkipCurrentLearnUnit,
                                  tutor,
                              }: DashboardViewProps) {
    const path = journey?.path ?? [];
    const passed = path.filter((item) => item.status === "COMPLETED").length;
    const skipped = path.filter((item) => item.status === "SKIPPED").length;
    const current = learnUnit;
    const hasDetailedContent = Boolean(current?.learnUnit.lessonIntro.trim() && current.learnUnit.examples.length);
    const next = path.find((item) => item.status === "PENDING");
    const nextStep = next
        ? learnUnitLabel(learnUnits, next.learnUnitCode)
        : current?.pathItem?.status === "CURRENT" ? "完成当前 LearnUnit" : "完成 Journey";

    return (
        <section className="journey-grid">
            <aside className="path panel">
                <div className="panel-title"><span>Learning path</span><span
                    className="muted">{passed} passed · {skipped} skipped</span></div>
                <div className="path-list">
                    {path.map((item) => {
                        const actionable = item.status === "CURRENT";
                        return <button className={`path-item ${item.status.toLowerCase()}`} key={item.learnUnitCode}
                                       onClick={() => actionable && void onOpenLearnUnit(item.learnUnitCode)}
                                       disabled={!actionable || busy}>
                            <span className="path-number">{item.sequence}</span>
                            <span><strong>{learnUnitLabel(learnUnits, item.learnUnitCode)}</strong><small>{pathStatusLabel(item.status)}</small></span>
                            <span
                                className="path-mark">{item.status === "COMPLETED" ? "✓" : item.status === "SKIPPED" ? "–" : item.status === "CURRENT" ? "→" : "·"}</span>
                        </button>;
                    })}
                    {!path.length && <p className="empty">完成诊断后生成路径。</p>}
                </div>
            </aside>
            <section className="lesson panel">
                {!current ? (
                    <div
                        className="empty">{journey?.journey.status === "COMPLETED" ? "恭喜，你已完成这条学习路径。" : "正在加载当前 LearnUnit…"}</div>
                ) : (
                    <>
                        <div className="lesson-header">
                            <div>
                                <div className="section-kicker">CURRENT LEARN UNIT</div>
                                <h2>{current.learnUnit.name}</h2></div>
                            <span
                                className="status-pill">{pathStatusLabel(current.pathItem?.status ?? "CURRENT")}</span>
                        </div>
                        <p className="lead">{hasDetailedContent ? current.learnUnit.lessonIntro : current.learnUnit.description}</p>
                        {!hasDetailedContent && <p className="generation-status" role="status">
                            这是已确认的大纲。点击“开始学习”后，Agent 才会生成这个单元的详细内容。
                        </p>}
                        <div className="lesson-stats">
                            <span>掌握度 {current.pathItem?.masteryScore ?? 0}</span>
                            <span>最佳成绩 {current.pathItem?.bestAssessmentScore ?? 0}</span>
                            <span>评估次数 {current.pathItem?.attemptCount ?? 0}</span>
                        </div>
                        <p className="next-step">下一步：{nextStep}</p>
                        <div className="lesson-columns">
                            <div><h3>学习目标</h3>
                                <ul>{current.learnUnit.learningObjectives.map((item) => <li
                                    key={item}>{item}</li>)}</ul>
                            </div>
                            <div><h3>关键概念</h3>
                                <div className="tag-list">{current.learnUnit.keyConcepts.map((item) => <span
                                    key={item}>{item}</span>)}</div>
                            </div>
                        </div>
                        {hasDetailedContent && <div className="example-block"><h3>Example</h3>{current.learnUnit.examples.map((item) => <p
                            key={item}>{item}</p>)}</div>}
                        {current.questionAttempts.filter((item) => item.feedback?.trim()).slice(0, 3).length > 0 && (
                            <div className="feedback-block">
                                <h3>最近反馈</h3>{current.questionAttempts.filter((item) => item.feedback?.trim()).slice(0, 3).map((item) =>
                                <p key={`${item.assessmentAttemptId}-${item.questionId}`}>{item.feedback}</p>)}</div>
                        )}
                        <div className="button-row">
                            <button className="primary" onClick={() => void onOpenLearnUnit(current.learnUnit.code)}
                                    disabled={busy || !currentLearnUnit} aria-busy={busy}>
                                {busy ? "处理中…" : hasDetailedContent ? "继续学习" : "开始学习"}
                            </button>
                            <button className="secondary" onClick={() => void onRetryCurrentLearnUnit()}
                                    disabled={busy || !canRetry}>Retry
                            </button>
                            <button className="primary" onClick={() => void onStartLearnUnitAssessment()}
                                    disabled={busy || !currentLearnUnit || !hasDetailedContent}>开始 LearnUnit 评估
                            </button>
                            <button className="secondary" onClick={() => void onSkipCurrentLearnUnit()}
                                    disabled={busy || !currentLearnUnit || hasOpenAttempt}>Skip
                            </button>
                        </div>
                    </>
                )}
            </section>
            <TutorPanel {...tutor} learnUnit={hasDetailedContent ? tutor.learnUnit : null} />
        </section>
    );
}
