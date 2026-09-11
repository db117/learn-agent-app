import {useEffect, useState} from "react";
import type {JourneyDetail, LearnUnit, LearnUnitResponse, LearningPhase} from "../lib/api";
import {TutorPanel, type TutorPanelProps} from "./TutorPanel";

type DashboardViewProps = {
    journey: JourneyDetail | null;
    learnUnits: LearnUnit[];
    learnUnit: LearnUnitResponse | null;
    busy: boolean;
    currentLearnUnit: boolean;
    hasOpenAttempt: boolean;
    canRetry: boolean;
    learnUnitLabel: (learnUnits: LearnUnit[], code: string | null) => string;
    onOpenLearnUnit: (code: string) => void | Promise<void>;
    onOpenReviewLearnUnit: (code: string) => void | Promise<void>;
    onStartChapterSynthesis: (chapterCode: string) => void | Promise<void>;
    onRetryCurrentLearnUnit: () => void | Promise<void>;
    onStartLearnUnitAssessment: () => void | Promise<void>;
    onAdvancePhase: (phase: LearningPhase) => void | Promise<void>;
    onSkipPhase: (phase: LearningPhase) => void | Promise<void>;
    onGuidedPractice: (response: string) => void | Promise<void>;
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

function phaseLabel(phase: LearningPhase) {
    switch (phase) {
        case "EXPLANATION": return "Explanation";
        case "EXAMPLE": return "Example";
        case "GUIDED_PRACTICE": return "Guided practice";
        case "INDEPENDENT_CHECK": return "Independent check";
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
                                  onOpenReviewLearnUnit,
                                  onStartChapterSynthesis,
                                  onRetryCurrentLearnUnit,
                                  onStartLearnUnitAssessment,
                                  onAdvancePhase,
                                  onSkipPhase,
                                  onGuidedPractice,
                                  onSkipCurrentLearnUnit,
                                  tutor,
                              }: DashboardViewProps) {
    const path = journey?.path ?? [];
    const chapters = journey?.chapters ?? [];
    const passed = path.filter((item) => item.status === "COMPLETED").length;
    const skipped = path.filter((item) => item.status === "SKIPPED").length;
    const reviewDebt = path.find((item) => item.needsReview);
    const current = learnUnit;
    const phase = current?.pathItem?.learningPhase ?? "EXPLANATION";
    const hasDetailedContent = Boolean(
        current?.learnUnit.lessonIntro.trim() && current.learnUnit.examples.length &&
        current.learnUnit.ability.trim() && current.learnUnit.guidedPracticePrompt.trim() &&
        current.learnUnit.independentCheckPrompt.trim() && current.learnUnit.estimatedMinutes > 0,
    );
    const [guidedResponse, setGuidedResponse] = useState("");
    useEffect(() => setGuidedResponse(""), [current?.learnUnit.code, phase]);
    const next = path.find((item) => item.status === "PENDING");
    const availableSynthesis = chapters.filter((entry) => entry.synthesisAvailable && !entry.synthesisCompleted);
    const nextStep = next
        ? learnUnitLabel(learnUnits, next.learnUnitCode)
        : availableSynthesis.length ? "完成 Chapter synthesis" : current?.pathItem?.status === "CURRENT" ? "完成当前 LearnUnit" : "完成 Journey";

    return (
        <section className="journey-grid">
            <aside className="path panel">
                <div className="panel-title"><span>Learning path</span><span
                    className="muted">{passed} passed · {skipped} skipped</span></div>
                {reviewDebt && <p className="warning" role="status">
                    Review needed: {learnUnitLabel(learnUnits, reviewDebt.learnUnitCode)}
                </p>}
                {chapters.map((entry) => <details className="path-chapter" key={entry.chapter.code}
                                                     open={entry.path.some((item) => item.status === "CURRENT")}>
                    <summary>
                        <span><strong>{entry.chapter.sequence}. {entry.chapter.name}</strong><small>{entry.chapter.goal}</small></span>
                        <span className="muted">{entry.completedCount}/{entry.learnUnits.length} passed · {entry.unresolvedCount} unresolved</span>
                    </summary>
                    <div className="path-list">
                        {entry.path.map((item) => {
                            const actionable = item.status === "CURRENT";
                            const reviewable = item.needsReview && item.status !== "PENDING";
                            const name = entry.learnUnits.find((unit) => unit.code === item.learnUnitCode)?.name
                                ?? learnUnitLabel(learnUnits, item.learnUnitCode);
                            return <button className={`path-item ${item.status.toLowerCase()}`} key={item.learnUnitCode}
                                           onClick={() => actionable
                                               ? void onOpenLearnUnit(item.learnUnitCode)
                                               : reviewable && void onOpenReviewLearnUnit(item.learnUnitCode)}
                                           disabled={(!actionable && !reviewable) || busy}>
                                <span className="path-number">{item.sequence}</span>
                                <span>
                                    <strong>{name}</strong>
                                    <small>{item.needsReview ? "Review needed" : pathStatusLabel(item.status)}</small>
                                    {item.skippedPhases.length > 0 &&
                                        <small>Skipped phases: {item.skippedPhases.map(phaseLabel).join(", ")}</small>}
                                </span>
                                <span className="path-mark">{item.status === "COMPLETED" ? "✓" : item.status === "SKIPPED" ? "–" : item.status === "CURRENT" ? "→" : "·"}</span>
                            </button>;
                        })}
                        {entry.synthesisAvailable && !entry.synthesisCompleted && <button className="secondary chapter-synthesis-button"
                                                                                         onClick={() => void onStartChapterSynthesis(entry.chapter.code)}
                                                                                         disabled={busy}>
                            开始 Chapter synthesis
                        </button>}
                        {entry.synthesisCompleted && <p className="success">Chapter synthesis 已通过</p>}
                    </div>
                </details>)}
                {!chapters.length && <p className="empty">完成 Journey 大纲后生成 Chapter 路径。</p>}
            </aside>
            <section className="lesson panel">
                {!current ? (
                    <div className="empty">
                        {journey?.journey.status === "COMPLETED" ? "恭喜，你已完成这条学习路径。" : availableSynthesis.length > 0 ? <>
                            <p>本章 LearnUnit 已 traversed。请完成 Chapter synthesis。</p>
                            {availableSynthesis.map((entry) => <button className="primary" key={entry.chapter.code}
                                                                        onClick={() => void onStartChapterSynthesis(entry.chapter.code)}
                                                                        disabled={busy}>
                                {entry.chapter.name} · 开始 synthesis
                            </button>)}
                        </> : "正在加载当前 LearnUnit…"}
                    </div>
                ) : (
                    <>
                        <div className="lesson-header">
                            <div>
                                <div className="section-kicker">CURRENT LEARN UNIT</div>
                                <h2>{current.learnUnit.name}</h2></div>
                            <span
                                className="status-pill">{pathStatusLabel(current.pathItem?.status ?? "CURRENT")}</span>
                        </div>
                        <p className="lead">{hasDetailedContent ? current.learnUnit.ability : current.learnUnit.description}</p>
                        {!hasDetailedContent && <p className="generation-status" role="status">
                            这是已确认的大纲。点击“开始学习”后，Agent 才会生成这个单元的详细内容。
                        </p>}
                        <div className="lesson-stats">
                            <span>掌握度 {current.pathItem?.masteryScore ?? 0}</span>
                            <span>最佳成绩 {current.pathItem?.bestAssessmentScore ?? 0}</span>
                            <span>评估次数 {current.pathItem?.attemptCount ?? 0}</span>
                        </div>
                        <p className="next-step">下一步：{nextStep}</p>
                        {current.pathItem?.needsReview && <p className="warning" role="status">
                            Independent check 未通过。先针对“{current.learnUnit.ability || current.learnUnit.name}”进行 remediation，再用相同题集 Retry；这项能力仍未掌握。
                        </p>}
                        {hasDetailedContent && <>
                            <div className="phase-header">
                                <span className="section-kicker">PHASE {phaseLabel(phase)}</span>
                                <span className="muted">预计 {current.learnUnit.estimatedMinutes} 分钟</span>
                            </div>
                            <div className="phase-content">
                                {phase === "EXPLANATION" && <>
                                    <h3>Explanation</h3>
                                    <p>{current.learnUnit.lessonIntro}</p>
                                </>}
                                {phase === "EXAMPLE" && <>
                                    <h3>Example</h3>
                                    {current.learnUnit.examples.map((item) => <p key={item}>{item}</p>)}
                                </>}
                                {phase === "GUIDED_PRACTICE" && <>
                                    <h3>Guided practice</h3>
                                    <p>{current.learnUnit.guidedPracticePrompt}</p>
                                    {current.learnUnit.guidedPracticeHints.length > 0 && <ul>
                                        {current.learnUnit.guidedPracticeHints.map((item) => <li key={item}>{item}</li>)}
                                    </ul>}
                                    <textarea value={guidedResponse}
                                              onChange={(event) => setGuidedResponse(event.target.value)}
                                              rows={5} placeholder="写下你的练习…" aria-label="Guided practice response"/>
                                    <button className="secondary" onClick={() => void onGuidedPractice(guidedResponse)}
                                            disabled={busy || !guidedResponse.trim()}>保存练习反馈</button>
                                    {current.pathItem?.guidedPracticeEntries.map((entry) =>
                                        <div className="feedback-block" key={entry.createdAt}>
                                            <p>{entry.response}</p><p className="muted">{entry.feedback}</p>
                                        </div>)}
                                </>}
                                {phase === "INDEPENDENT_CHECK" && <>
                                    <h3>Independent check</h3>
                                    <p>{current.learnUnit.independentCheckPrompt}</p>
                                    {current.pathItem?.skippedPhases.includes("INDEPENDENT_CHECK") &&
                                        <p className="warning" role="status">Independent check 尚未完成；跳过不会算作掌握。</p>}
                                    <button className="primary" onClick={() => void onStartLearnUnitAssessment()}
                                            disabled={busy || !currentLearnUnit || hasOpenAttempt}>
                                        {hasOpenAttempt ? "继续独立检查" : "开始独立检查"}
                                    </button>
                                </>}
                            </div>
                            <div className="phase-actions">
                                <button className="primary" onClick={() => void onAdvancePhase(phase)}
                                        disabled={busy || !currentLearnUnit || phase === "INDEPENDENT_CHECK"}>
                                    进入下一阶段
                                </button>
                                <button className="secondary" onClick={() => void onSkipPhase(phase)}
                                        disabled={busy || !currentLearnUnit || current.pathItem?.skippedPhases.includes(phase)}>
                                    跳过本阶段
                                </button>
                            </div>
                        </>}
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
