import {useEffect, useMemo, useState} from "react";
import {PracticePanel} from "./PracticePanel";
import type {PracticeDiagnostic} from "./practiceTypes";
import {createWorkspaceApi, type WorkspaceFileEntry} from "../workspace/workspaceApi";
import {beginSave, editDraft, failSave, finishSave, initialSaveState, selectFile} from "../workspace/saveState";

const BACKEND_URL = "http://127.0.0.1:18080";

type Props = {
    journeyId: number;
    onDirtyChange?: (dirty: boolean) => void;
    onProgressChanged?: (status: string, currentLearnUnitCode: string | null) => void;
    progressVersion?: number;
};

type CompileResponse = {
    success: boolean;
    diagnostics: PracticeDiagnostic[];
};

type TestResponse = {
    success: boolean;
    passed: boolean;
    testCount: number;
};

type LearningProgress = {
    journeyId: number;
    learningJourneyId: number;
    status: string;
    currentLearnUnitCode: string | null;
    currentLearnUnitTitle: string | null;
    currentLearnUnitObjective: string | null;
    currentMasteryScore: number;
    currentBestScore: number;
    practiceVerified: boolean;
    assessmentPassed: boolean;
    completedCount: number;
    totalCount: number;
    assessment: {
        learnUnitCode: string;
        passingScore: number;
        questions: Array<{
            code: string;
            type: string;
            prompt: string;
            optionIds: string[];
        }>;
    } | null;
};

type VerifyResponse = {
    verified: boolean;
    learningJourneyStatus: string;
    currentLearnUnitCode: string | null;
    advanced: boolean;
};

export function PracticeWorkspace({journeyId, onDirtyChange, onProgressChanged, progressVersion = 0}: Props) {
    const api = useMemo(() => createWorkspaceApi(fetch, BACKEND_URL), []);
    const [files, setFiles] = useState<WorkspaceFileEntry[]>([]);
    const [state, setState] = useState(initialSaveState);
    const [loading, setLoading] = useState(true);
    const [loadingContent, setLoadingContent] = useState(false);
    const [saving, setSaving] = useState(false);
    const [compiling, setCompiling] = useState(false);
    const [testing, setTesting] = useState(false);
    const [verifying, setVerifying] = useState(false);
    const [diagnostics, setDiagnostics] = useState<PracticeDiagnostic[]>([]);
    const [feedback, setFeedback] = useState<string | null>(null);
    const [progress, setProgress] = useState<LearningProgress | null>(null);
    const [progressLoading, setProgressLoading] = useState(true);
    const [assessmentAnswers, setAssessmentAnswers] = useState<Record<string, string[]>>({});
    const [assessing, setAssessing] = useState(false);

    useEffect(() => {
        onDirtyChange?.(state.dirty);
    }, [onDirtyChange, state.dirty]);

    useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

    const loadProgress = async (signal?: AbortSignal) => {
        setProgressLoading(true);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/learning`, {signal});
            if (!response.ok) throw new Error("无法读取学习进度");
            const next = await response.json() as LearningProgress;
            if (!signal?.aborted) {
                setProgress(next);
                setAssessmentAnswers({});
            }
        } catch (error: unknown) {
            if (!signal?.aborted) setFeedback(error instanceof Error ? error.message : "无法读取学习进度");
        } finally {
            if (!signal?.aborted) setProgressLoading(false);
        }
    };

    const readFile = async (path: string, signal?: AbortSignal) => {
        setLoadingContent(true);
        setFeedback(null);
        try {
            const file = await api.readFile("journey", journeyId, path);
            if (!signal?.aborted) setState(selectFile(file.path, file.content));
        } catch (error: unknown) {
            if (!signal?.aborted) setFeedback(error instanceof Error ? error.message : "无法读取练习文件");
        } finally {
            if (!signal?.aborted) setLoadingContent(false);
        }
    };

    useEffect(() => {
        const controller = new AbortController();
        setState(initialSaveState());
        setFiles([]);
        setDiagnostics([]);
        setFeedback(null);
        setLoading(true);
        void loadProgress(controller.signal);
        api.listFiles("journey", journeyId)
            .then((nextFiles) => {
                if (controller.signal.aborted) return;
                setFiles(nextFiles);
                setLoading(false);
                if (nextFiles[0]?.path) void readFile(nextFiles[0].path, controller.signal);
            })
            .catch((error: unknown) => {
                if (controller.signal.aborted) return;
                setLoading(false);
                setFeedback(error instanceof Error ? error.message : "无法读取练习 Workspace");
            });
        return () => controller.abort();
    }, [api, journeyId, progressVersion]);

    const selectWorkspaceFile = (path: string) => {
        if (path === state.selectedPath) return;
        if (state.dirty) {
            setFeedback("当前文件有未保存修改，请先保存。");
            return;
        }
        void readFile(path);
    };

    const save = async () => {
        if (!state.selectedPath || !state.dirty || saving) return;
        const path = state.selectedPath;
        setSaving(true);
        setState((current) => beginSave(current));
        setFeedback(null);
        try {
            const saved = await api.writeFile("journey", journeyId, path, state.draftContent);
            setState((current) => finishSave(current, saved.content));
            setFiles((current) => current.map((file) => file.path === saved.path ? saved : file));
        } catch (error: unknown) {
            const message = error instanceof Error ? error.message : "无法保存练习文件";
            setState((current) => failSave(current, message));
            setFeedback(message);
        } finally {
            setSaving(false);
        }
    };

    const compile = async () => {
        if (state.dirty || compiling || testing) {
            if (state.dirty) setFeedback("请先保存当前文件，再执行编译。");
            return;
        }
        setCompiling(true);
        setFeedback(null);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/practice/compile`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
            });
            if (!response.ok) throw new Error("编译请求失败");
            const result = await response.json() as CompileResponse;
            setDiagnostics(result.diagnostics);
            setFeedback(result.success ? "TypeScript 编译通过。" : "TypeScript 编译失败，请查看诊断。");
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法执行编译");
        } finally {
            setCompiling(false);
        }
    };

    const runTests = async () => {
        if (state.dirty || compiling || testing) {
            if (state.dirty) setFeedback("请先保存当前文件，再执行测试。");
            return;
        }
        setTesting(true);
        setFeedback(null);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/practice/tests`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
            });
            if (!response.ok) throw new Error("测试请求失败");
            const result = await response.json() as TestResponse;
            setFeedback(result.passed
                ? `测试通过（${result.testCount} 个）。`
                : `测试未通过（${result.testCount} 个）。`);
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法执行测试");
        } finally {
            setTesting(false);
        }
    };

    const verify = async () => {
        if (state.dirty || compiling || testing || verifying) {
            if (state.dirty) setFeedback("请先保存当前文件，再验证 Practice。");
            return;
        }
        setVerifying(true);
        setFeedback(null);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/practice/verify`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
            });
            if (!response.ok) throw new Error("Practice 验证请求失败");
            const result = await response.json() as VerifyResponse;
            setFeedback(result.advanced
                ? "Practice 已通过，已进入下一个 LearnUnit。"
                : result.verified
                    ? "Practice 已通过，请完成当前单元的 Assessment。"
                    : "Practice 尚未通过，请根据编译和测试结果继续修改。");
            onProgressChanged?.(result.learningJourneyStatus, result.currentLearnUnitCode);
            await loadProgress();
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法验证 Practice");
        } finally {
            setVerifying(false);
        }
    };

    const chooseAnswer = (questionCode: string, optionId: string, multiple: boolean) => {
        setAssessmentAnswers((current) => {
            const selected = current[questionCode] ?? [];
            const next = multiple
                ? selected.includes(optionId)
                    ? selected.filter((value) => value !== optionId)
                    : [...selected, optionId]
                : [optionId];
            return {...current, [questionCode]: next};
        });
    };

    const submitAssessment = async () => {
        const assessment = progress?.assessment;
        if (!assessment || !progress.currentLearnUnitCode || assessing) return;
        const hasUnsupportedQuestion = assessment.questions.some((question) => question.type === "CODE");
        const missingAnswer = assessment.questions.some(
            (question) => (assessmentAnswers[question.code] ?? []).length === 0,
        );
        if (hasUnsupportedQuestion || missingAnswer) {
            setFeedback(hasUnsupportedQuestion
                ? "当前 Assessment 的编码题需要后续确定性评估器。"
                : "请先回答全部 Assessment 问题。");
            return;
        }
        setAssessing(true);
        setFeedback(null);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/assessment`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    learnUnitCode: progress.currentLearnUnitCode,
                    answers: assessment.questions.map((question) => ({
                        questionCode: question.code,
                        selectedOptionIds: assessmentAnswers[question.code] ?? [],
                        workspaceReference: null,
                    })),
                }),
            });
            if (!response.ok) throw new Error("Assessment 提交失败");
            const next = await response.json() as LearningProgress;
            setProgress(next);
            setAssessmentAnswers({});
            setFeedback(next.status === "COMPLETED"
                ? "Assessment 通过，学习路径已完成。"
                : next.currentLearnUnitCode !== progress.currentLearnUnitCode
                    ? "Assessment 通过，已进入下一个 LearnUnit。"
                    : next.assessmentPassed
                        ? "Assessment 已通过，请完成 Practice。"
                        : "Assessment 未通过，可以重新提交。 ");
            onProgressChanged?.(next.status, next.currentLearnUnitCode);
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法提交 Assessment");
        } finally {
            setAssessing(false);
        }
    };

    return <>
        <PracticePanel
            files={files}
            selectedPath={state.selectedPath}
            content={state.draftContent}
            loading={loading}
            loadingContent={loadingContent}
            dirty={state.dirty}
            saving={saving}
            compiling={compiling}
            testing={testing}
            verifying={verifying}
            practiceVerified={progress?.practiceVerified}
            feedback={feedback}
            diagnostics={diagnostics}
            onSelectFile={selectWorkspaceFile}
            onContentChange={(content) => setState((current) => editDraft(current, content))}
            onSave={save}
            onCompile={compile}
            onTest={runTests}
            onVerify={verify}
        />
        {progressLoading ? (
            <p className="session-status">正在读取当前学习单元…</p>
        ) : progress?.status === "COMPLETED" ? (
            <section className="status-card" aria-labelledby="learning-complete-title">
                <h3 id="learning-complete-title">学习路径已完成</h3>
                <p>已完成 {progress.completedCount} / {progress.totalCount} 个 LearnUnit。</p>
            </section>
        ) : progress?.assessment && (
            <section className="status-card" aria-labelledby="assessment-title">
                <div className="card-heading">
                    <h3 id="assessment-title">Independent Assessment</h3>
                    <span className="journey-status">及格线 {progress.assessment.passingScore}</span>
                </div>
                <p className="planning-note">
                    当前：{progress.currentLearnUnitTitle ?? progress.currentLearnUnitCode}
                    {progress.practiceVerified ? " · Practice 已通过" : " · 先完成 Practice"}
                </p>
                {progress.assessmentPassed ? (
                    <p className="form-feedback" role="status">Assessment 已通过，请完成 Practice。</p>
                ) : (
                    <div className="assessment-list">
                        {progress.assessment.questions.map((question) => {
                            const multiple = question.type === "MULTIPLE_CHOICE";
                            const selected = assessmentAnswers[question.code] ?? [];
                            return (
                                <fieldset key={question.code} className="assessment-question">
                                    <legend>{question.prompt}</legend>
                                    {question.type === "CODE" ? (
                                        <p className="session-status">编码题将在确定性代码评估器接入后开放。</p>
                                    ) : question.optionIds.map((optionId) => (
                                        <label key={optionId}>
                                            <input
                                                type={multiple ? "checkbox" : "radio"}
                                                name={question.code}
                                                checked={selected.includes(optionId)}
                                                onChange={() => chooseAnswer(question.code, optionId, multiple)}
                                            />
                                            {optionId}
                                        </label>
                                    ))}
                                </fieldset>
                            );
                        })}
                        <button type="button" onClick={() => void submitAssessment()} disabled={assessing}>
                            {assessing ? "提交中…" : "提交 Assessment"}
                        </button>
                    </div>
                )}
            </section>
        )}
    </>;
}
