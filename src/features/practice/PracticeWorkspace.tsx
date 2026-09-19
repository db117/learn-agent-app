import {useEffect, useMemo, useState} from "react";
import {type LearningProgress, LearnModePanel} from "../learn/LearnModePanel";
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
    summary: string;
    diagnostics: PracticeDiagnostic[];
};

type TestResponse = {
    success: boolean;
    summary: string;
    passed: boolean;
    testCount: number;
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
    const [runtimeSummary, setRuntimeSummary] = useState<string | null>(null);
    const [feedback, setFeedback] = useState<string | null>(null);
    const [progress, setProgress] = useState<LearningProgress | null>(null);
    const [progressLoading, setProgressLoading] = useState(true);

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
        setRuntimeSummary(null);
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
        setRuntimeSummary(null);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/practice/compile`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: "{}",
            });
            if (!response.ok) throw new Error("编译请求失败");
            const result = await response.json() as CompileResponse;
            setDiagnostics(result.diagnostics);
            setRuntimeSummary(result.summary);
            setFeedback(result.success ? "TypeScript 编译通过。" : "TypeScript 编译失败，请查看执行摘要和诊断。");
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
        setRuntimeSummary(null);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/practice/tests`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: "{}",
            });
            if (!response.ok) throw new Error("测试请求失败");
            const result = await response.json() as TestResponse;
            setRuntimeSummary(result.summary);
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
                    ? "Practice 已记录。"
                    : "Practice 尚未通过，请根据编译和测试结果继续修改。");
            onProgressChanged?.(result.learningJourneyStatus, result.currentLearnUnitCode);
            await loadProgress();
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法验证 Practice");
        } finally {
            setVerifying(false);
        }
    };

    return <>
        <LearnModePanel progress={progress} loading={progressLoading}/>
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
            runtimeSummary={runtimeSummary}
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
        ) : null}
    </>;
}
