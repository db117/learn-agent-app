import Editor from "@monaco-editor/react";
import {useEffect, useMemo, useState} from "react";
import {buildFileTree} from "./fileTree";
import {WorkspaceFileTree} from "./WorkspaceFileTree";
import {beginSave, editDraft, failSave, finishSave, initialSaveState, selectFile} from "./saveState";
import {createWorkspaceApi, type WorkspaceFileEntry} from "./workspaceApi";

const BACKEND_URL = "http://127.0.0.1:10707";

type Props = {
    journeyId: number;
    onDirtyChange?: (dirty: boolean) => void;
};

export function WorkspacePanel({journeyId, onDirtyChange}: Props) {
    const api = useMemo(() => createWorkspaceApi(fetch, BACKEND_URL), []);
    const [files, setFiles] = useState<WorkspaceFileEntry[]>([]);
    const [state, setState] = useState(initialSaveState);
    const [loading, setLoading] = useState(true);
    const [loadingFile, setLoadingFile] = useState(false);
    const [message, setMessage] = useState<string | null>(null);

    useEffect(() => {
        onDirtyChange?.(state.dirty);
    }, [onDirtyChange, state.dirty]);

    useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

    useEffect(() => {
        const controller = new AbortController();
        setState(initialSaveState());
        setFiles([]);
        setLoadingFile(false);
        setMessage(null);
        setLoading(true);
        api.listFiles("journey", journeyId)
            .then((nextFiles) => {
                if (controller.signal.aborted) return;
                setFiles(nextFiles);
                setLoading(false);
                const firstPath = nextFiles[0]?.path;
                if (firstPath) void readFile(firstPath, controller.signal);
            })
            .catch((error: unknown) => {
                if (controller.signal.aborted) return;
                setLoading(false);
                setMessage(error instanceof Error ? error.message : "无法读取 Workspace 文件");
            });
        return () => controller.abort();
    }, [api, journeyId]);

    useEffect(() => {
        const warnBeforeLeave = (event: BeforeUnloadEvent) => {
            if (!state.dirty) return;
            event.preventDefault();
            event.returnValue = "";
        };
        window.addEventListener("beforeunload", warnBeforeLeave);
        return () => window.removeEventListener("beforeunload", warnBeforeLeave);
    }, [state.dirty]);

    const readFile = async (path: string, signal?: AbortSignal) => {
        setLoadingFile(true);
        setMessage(null);
        try {
            const file = await api.readFile("journey", journeyId, path);
            if (!signal?.aborted) setState(selectFile(file.path, file.content));
        } catch (error: unknown) {
            if (!signal?.aborted) setMessage(error instanceof Error ? error.message : "无法读取 Workspace 文件");
        } finally {
            if (!signal?.aborted) setLoadingFile(false);
        }
    };

    const selectWorkspaceFile = (path: string) => {
        if (path === state.selectedPath) return;
        if (state.dirty) {
            setMessage("当前文件有未保存修改，请先保存。");
            return;
        }
        void readFile(path);
    };

    const refresh = () => {
        if (state.dirty) {
            setMessage("当前文件有未保存修改，请先保存后再刷新。");
            return;
        }
        setLoading(true);
        setMessage(null);
        api.listFiles("journey", journeyId)
            .then((nextFiles) => {
                setFiles(nextFiles);
                setLoading(false);
                if (state.selectedPath && nextFiles.some((file) => file.path === state.selectedPath)) {
                    void readFile(state.selectedPath);
                }
            })
            .catch((error: unknown) => {
                setLoading(false);
                setMessage(error instanceof Error ? error.message : "无法刷新 Workspace 文件");
            });
    };

    const save = async () => {
        if (!state.selectedPath || !state.dirty || state.saving) return;
        const path = state.selectedPath;
        const content = state.draftContent;
        setState((current) => beginSave(current));
        setMessage(null);
        try {
            const saved = await api.writeFile("journey", journeyId, path, content);
            setState((current) => finishSave(current, saved.content));
            setFiles((current) => current.map((file) => file.path === saved.path ? saved : file));
        } catch (error: unknown) {
            const errorMessage = error instanceof Error ? error.message : "无法保存 Workspace 文件";
            setState((current) => failSave(current, errorMessage));
            setMessage(errorMessage);
        }
    };

    const tree = buildFileTree(files);
    return (
        <section className="workspace-panel" aria-labelledby="workspace-title">
            <div className="workspace-heading">
                <div>
                    <p className="mode-label">LEARNING WORKSPACE</p>
                    <h3 id="workspace-title">代码文件</h3>
                </div>
                <div className="workspace-actions">
                    <button type="button" className="secondary" onClick={refresh} disabled={loading || loadingFile}>
                        刷新
                    </button>
                    <button type="button" onClick={() => void save()}
                            disabled={!state.selectedPath || !state.dirty || state.saving || loadingFile}>
                        {state.saving ? "保存中…" : "保存"}
                    </button>
                </div>
            </div>
            {message && <p className="form-feedback" role="alert">{message}</p>}
            <div className="workspace-layout">
                <nav className="workspace-files" aria-label="Workspace 文件树">
                    {loading ? <p className="empty-state">正在读取文件…</p> : <WorkspaceFileTree
                        tree={tree}
                        selectedPath={state.selectedPath}
                        onSelect={selectWorkspaceFile}
                    />}
                </nav>
                <div className="workspace-editor">
                    <p className="workspace-path" aria-live="polite">
                        {state.selectedPath ?? "请选择文件"}{state.dirty ? " · 未保存" : ""}
                    </p>
                    {state.selectedPath ? (
                        <Editor
                            height="360px"
                            language="typescript"
                            theme="vs-dark"
                            value={state.draftContent}
                            onChange={(value) => setState((current) => editDraft(current, value ?? ""))}
                            options={{fontSize: 14, minimap: {enabled: false}, wordWrap: "on"}}
                        />
                    ) : (
                        <p className="empty-state">Workspace 中还没有可编辑文件。</p>
                    )}
                </div>
            </div>
        </section>
    );
}
