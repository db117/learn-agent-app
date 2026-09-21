import {useEffect, useMemo, useState} from "react";
import {LearningPathPanel, type LearningProgress, LearnModePanel} from "../learn/LearnModePanel";
import {PracticePanel} from "./PracticePanel";
import type {ChoiceQuestion, PracticeDiagnostic} from "./practiceTypes";
import {createWorkspaceApi, type WorkspaceFileEntry} from "../workspace/workspaceApi";
import {beginSave, editDraft, failSave, finishSave, initialSaveState, selectFile} from "../workspace/saveState";

const BACKEND_URL = "http://127.0.0.1:18080";

type Props = {
    journeyId: number;
    onDirtyChange?: (dirty: boolean) => void;
    onProgressChanged?: (status: string, currentLearnUnitCode: string | null) => void;
    theme?: "dark" | "light";
    workspaceVersion?: number;
    progressVersion?: number;
    contentVersion?: number;
    learningLayout?: boolean;
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

type ChoiceResponse = {
    available: boolean;
    codeVerified: boolean;
    taskId: number | null;
    title: string | null;
    prompt: string | null;
    options: ChoiceQuestion["options"];
};

type ChoiceVerifyResponse = {
    taskId: number;
    status: string;
    verified: boolean;
    choiceCorrect: boolean;
    learningJourneyStatus: string;
    currentLearnUnitCode: string | null;
    advanced: boolean;
};

export function PracticeWorkspace({
                                      journeyId,
                                      onDirtyChange,
                                      onProgressChanged,
                                      theme = "dark",
                                      workspaceVersion = 0,
                                      progressVersion = 0,
                                      contentVersion = 0,
                                      learningLayout = false,
                                  }: Props) {
    const api = useMemo(() => createWorkspaceApi(fetch, BACKEND_URL), []);
    const [files, setFiles] = useState<WorkspaceFileEntry[]>([]);
    const [state, setState] = useState(initialSaveState);
    const [loading, setLoading] = useState(true);
    const [loadingContent, setLoadingContent] = useState(false);
    const [creatingFile, setCreatingFile] = useState(false);
    const [saving, setSaving] = useState(false);
    const [compiling, setCompiling] = useState(false);
    const [testing, setTesting] = useState(false);
    const [verifying, setVerifying] = useState(false);
    const [diagnostics, setDiagnostics] = useState<PracticeDiagnostic[]>([]);
    const [runtimeSummary, setRuntimeSummary] = useState<string | null>(null);
    const [feedback, setFeedback] = useState<string | null>(null);
    const [progress, setProgress] = useState<LearningProgress | null>(null);
    const [progressLoading, setProgressLoading] = useState(true);
    const [choiceQuestion, setChoiceQuestion] = useState<ChoiceQuestion | null>(null);
    const [choiceLoading, setChoiceLoading] = useState(true);
    const [codeVerified, setCodeVerified] = useState(false);
    const [choiceSubmitting, setChoiceSubmitting] = useState(false);
    const [choiceFeedback, setChoiceFeedback] = useState<string | null>(null);
    const [selectedChoiceId, setSelectedChoiceId] = useState<string | null>(null);
    const [practiceFullscreen, setPracticeFullscreen] = useState(false);

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

    const loadChoiceQuestion = async (signal?: AbortSignal) => {
        setChoiceLoading(true);
        try {
            const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/practice/choice`, {signal});
            if (!response.ok) throw new Error("无法读取已保存选择题");
            const next = await response.json() as ChoiceResponse;
            if (!signal?.aborted) {
                setCodeVerified(next.codeVerified);
                setChoiceQuestion(next.available && next.taskId !== null && next.title !== null && next.prompt !== null
                    ? {
                        taskId: next.taskId,
                        title: next.title,
                        prompt: next.prompt,
                        options: next.options,
                    }
                    : null);
                setSelectedChoiceId(null);
                setChoiceFeedback(null);
            }
        } catch (error: unknown) {
            if (!signal?.aborted) {
                setChoiceQuestion(null);
                setFeedback(error instanceof Error ? error.message : "无法读取已保存选择题");
            }
        } finally {
            if (!signal?.aborted) setChoiceLoading(false);
        }
    };

    useEffect(() => {
        const controller = new AbortController();
        setState(initialSaveState());
        setFiles([]);
        setDiagnostics([]);
        setRuntimeSummary(null);
        setFeedback(null);
        setChoiceQuestion(null);
        setChoiceLoading(true);
        setCodeVerified(false);
        setChoiceSubmitting(false);
        setChoiceFeedback(null);
        setSelectedChoiceId(null);
        setCreatingFile(false);
        setLoading(true);
        void loadProgress(controller.signal);
        void loadChoiceQuestion(controller.signal);
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

    useEffect(() => {
        if (workspaceVersion === 0) return;
        let cancelled = false;
        api.listFiles("journey", journeyId)
            .then((nextFiles) => {
                if (!cancelled) setFiles(nextFiles);
            })
            .catch((error: unknown) => {
                if (!cancelled) setFeedback(error instanceof Error ? error.message : "无法刷新练习 Workspace");
            });
        return () => {
            cancelled = true;
        };
    }, [api, journeyId, workspaceVersion]);

    useEffect(() => {
        if (contentVersion === 0) return;
        const controller = new AbortController();
        void loadProgress(controller.signal);
        void loadChoiceQuestion(controller.signal);
        return () => controller.abort();
    }, [contentVersion, journeyId]);

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

    const createFile = async (path: string) => {
        const normalizedPath = path.trim();
        if (!normalizedPath) {
            setFeedback("请输入新文件的 Workspace 相对路径。");
            return;
        }
        if (files.some((file) => file.path === normalizedPath)) {
            setFeedback("文件已存在，请直接从文件树中打开它。");
            return;
        }
        setCreatingFile(true);
        setFeedback(null);
        try {
            const file = await api.writeFile("journey", journeyId, normalizedPath, "");
            setFiles((current) => [...current, file].sort((left, right) => left.path.localeCompare(right.path)));
            setState(selectFile(file.path, file.content));
            setFeedback(`已创建 ${file.path}，现在可以编辑。`);
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法创建练习文件");
        } finally {
            setCreatingFile(false);
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
            setCodeVerified(result.verified && !result.advanced);
            setFeedback(result.advanced
                ? "Practice 已通过，已进入下一个 LearnUnit。"
                : result.verified
                    ? "Practice 已记录。"
                    : "Practice 尚未通过，请根据编译和测试结果继续修改。");
            if (result.advanced) {
                onProgressChanged?.(result.learningJourneyStatus, result.currentLearnUnitCode);
            }
            await loadProgress();
        } catch (error: unknown) {
            setFeedback(error instanceof Error ? error.message : "无法验证 Practice");
        } finally {
            setVerifying(false);
        }
    };

    const verifyChoice = async () => {
        if (!choiceQuestion || !selectedChoiceId || choiceSubmitting) return;
        setChoiceSubmitting(true);
        setChoiceFeedback(null);
        try {
            const response = await fetch(
                `${BACKEND_URL}/api/journeys/${journeyId}/practice/tasks/${choiceQuestion.taskId}/choice/verify`,
                {
                    method: "POST",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify({optionId: selectedChoiceId}),
                });
            if (!response.ok) throw new Error("选择题提交失败");
            const result = await response.json() as ChoiceVerifyResponse;
            if (!result.verified) {
                setChoiceFeedback("还不对，再试一次；可以回看上面的课程内容。");
                return;
            }
            setChoiceFeedback("回答正确，正在进入下一个 LearnUnit…");
            if (result.advanced) {
                onProgressChanged?.(result.learningJourneyStatus, result.currentLearnUnitCode);
            }
        } catch (error: unknown) {
            setChoiceFeedback(error instanceof Error ? error.message : "无法提交选择题");
        } finally {
            setChoiceSubmitting(false);
        }
    };

    const lessonPanel = <LearnModePanel
        progress={progress}
        loading={progressLoading}
        showPath={!learningLayout}
    />;
    const practicePanel = <PracticePanel
            files={files}
            selectedPath={state.selectedPath}
            content={state.draftContent}
            loading={loading}
            loadingContent={loadingContent}
            creating={creatingFile}
            dirty={state.dirty}
            saving={saving}
            compiling={compiling}
            testing={testing}
            verifying={verifying}
            practiceVerified={progress?.practiceVerified}
            codeVerified={codeVerified}
            choiceQuestion={choiceQuestion}
            choiceLoading={choiceLoading}
            choiceSubmitting={choiceSubmitting}
            choiceFeedback={choiceFeedback}
            selectedChoiceId={selectedChoiceId}
            feedback={feedback}
            runtimeSummary={runtimeSummary}
            diagnostics={diagnostics}
            onSelectFile={selectWorkspaceFile}
            onContentChange={(content) => setState((current) => editDraft(current, content))}
            onSave={save}
            onCompile={compile}
            onTest={runTests}
            onCreateFile={createFile}
            onVerify={verify}
            onSelectChoice={setSelectedChoiceId}
            onVerifyChoice={verifyChoice}
            theme={theme}
            fullscreen={practiceFullscreen}
            onToggleFullscreen={() => setPracticeFullscreen((current) => !current)}
    />;
    const completionMessage = progressLoading ? (
            <p className="session-status">正在读取当前学习单元…</p>
        ) : progress?.status === "COMPLETED" ? (
            <section className="status-card" aria-labelledby="learning-complete-title">
                <h3 id="learning-complete-title">学习路径已完成</h3>
                <p>已完成 {progress.completedCount} / {progress.totalCount} 个 LearnUnit。</p>
            </section>
    ) : null;

    if (!learningLayout) return <div className="learning-stack">
        {lessonPanel}
        {practicePanel}
        {completionMessage}
    </div>;

    return <>
        <aside className="learning-path-column">
            <LearningPathPanel progress={progress} loading={progressLoading}/>
        </aside>
        <div className="learning-content-column">{lessonPanel}</div>
        <div className="learning-practice-column">{practicePanel}</div>
        {completionMessage && <div className="learning-completion-column">{completionMessage}</div>}
    </>;
}
