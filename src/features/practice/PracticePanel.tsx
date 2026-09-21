import Editor from "@monaco-editor/react";
import {useEffect, useState} from "react";
import {buildFileTree} from "../workspace/fileTree";
import {WorkspaceFileTree} from "../workspace/WorkspaceFileTree";
import {formatDiagnostic, sortDiagnostics} from "./practiceDiagnostics";
import type {PracticePanelProps} from "./practiceTypes";

export function PracticePanel({
                                  files,
                                  selectedPath,
                                  content,
                                  loading = false,
                                  loadingContent = false,
                                  creating = false,
                                  dirty = false,
                                  saving = false,
                                  compiling = false,
                                  testing = false,
                                  verifying = false,
                                  practiceVerified = false,
                                  feedback = null,
                                  runtimeSummary = null,
                                  diagnostics = [],
                                  onSelectFile,
                                  onContentChange,
                                  onSave,
                                  onCompile,
                                  onTest,
                                  onCreateFile,
                                  onVerify = () => undefined,
                                  theme = "dark",
                                  fullscreen = false,
                                  onToggleFullscreen,
                              }: PracticePanelProps) {
    const [newFilePath, setNewFilePath] = useState("");

    useEffect(() => {
        if (!fullscreen) return;
        const closeOnEscape = (event: KeyboardEvent) => {
            if (event.key === "Escape") onToggleFullscreen?.();
        };
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        document.addEventListener("keydown", closeOnEscape);
        return () => {
            document.body.style.overflow = previousOverflow;
            document.removeEventListener("keydown", closeOnEscape);
        };
    }, [fullscreen, onToggleFullscreen]);

    const tree = buildFileTree([...files]);
    const orderedDiagnostics = sortDiagnostics(diagnostics);
    const actionsDisabled = loading || loadingContent || saving || creating;

    return (
        <section className={`workspace-panel ${fullscreen ? "workspace-panel-fullscreen" : ""}`}
                 aria-labelledby="practice-title">
            <div className="workspace-heading">
                <div>
                    <p className="mode-label">PRACTICE RUNTIME</p>
                    <h3 id="practice-title">代码练习</h3>
                </div>
                <div className="workspace-actions">
                    {onToggleFullscreen && (
                        <button type="button" className="secondary" onClick={onToggleFullscreen}
                                aria-pressed={fullscreen}>
                            {fullscreen ? "退出全屏" : "全屏编辑"}
                        </button>
                    )}
                    <button type="button" onClick={() => void onCompile()}
                            disabled={actionsDisabled || compiling || testing}
                            aria-busy={compiling}>
                        {compiling ? "编译中…" : "编译"}
                    </button>
                    <button type="button" className="secondary" onClick={() => void onTest()}
                            disabled={actionsDisabled || compiling || testing}
                            aria-busy={testing}>
                        {testing ? "测试中…" : "测试"}
                    </button>
                    <button type="button" className="secondary" onClick={() => void onSave()}
                            disabled={!selectedPath || !dirty || actionsDisabled}
                            aria-busy={saving}>
                        {saving ? "保存中…" : "保存"}
                    </button>
                    <button type="button" className="secondary" onClick={() => void onVerify()}
                            disabled={actionsDisabled || compiling || testing || verifying || practiceVerified}
                            aria-busy={verifying}>
                        {verifying ? "验证中…" : practiceVerified ? "Practice 已记录" : "验证并记录"}
                    </button>
                </div>
            </div>

            <form className="workspace-create" onSubmit={(event) => {
                event.preventDefault();
                void onCreateFile(newFilePath.trim());
            }}>
                <label htmlFor="new-practice-file">新建文件路径</label>
                <div className="workspace-create-controls">
                    <input
                        id="new-practice-file"
                        value={newFilePath}
                        onChange={(event) => setNewFilePath(event.target.value)}
                        placeholder="例如：ts-runtime-practice/src/index.ts"
                        required
                        disabled={actionsDisabled}
                    />
                    <button type="submit" className="secondary" disabled={actionsDisabled}>
                        {creating ? "创建中…" : "新建文件"}
                    </button>
                </div>
            </form>

            {feedback && <p className="form-feedback" role="alert">{feedback}</p>}
            {runtimeSummary && <pre className="runtime-summary" aria-label="执行摘要">{runtimeSummary}</pre>}

            <div className="workspace-layout">
                <nav className="workspace-files" aria-label="Practice Workspace 文件树">
                    {loading ? <p className="empty-state">正在读取文件…</p> : <WorkspaceFileTree
                        tree={tree}
                        selectedPath={selectedPath}
                        onSelect={onSelectFile}
                    />}
                </nav>
                <div className="workspace-editor">
                    <p className="workspace-path" aria-live="polite">
                        {selectedPath ?? "请选择文件"}{dirty ? " · 未保存" : ""}
                    </p>
                    {loadingContent ? (
                        <p className="empty-state">正在读取文件…</p>
                    ) : selectedPath ? (
                        <Editor
                            height={fullscreen ? "calc(100vh - 16rem)" : "520px"}
                            language="typescript"
                            theme={theme === "light" ? "light" : "vs-dark"}
                            value={content}
                            onChange={(value) => onContentChange(value ?? "")}
                            options={{fontSize: 14, minimap: {enabled: false}, wordWrap: "on"}}
                        />
                    ) : (
                        <p className="empty-state">Workspace 中还没有可编辑文件。</p>
                    )}
                </div>
            </div>

            <section aria-labelledby="practice-diagnostics-title">
                <h4 id="practice-diagnostics-title">诊断</h4>
                {orderedDiagnostics.length === 0 ? (
                    <p className="session-status">暂无编译诊断。</p>
                ) : (
                    <ol aria-label="TypeScript 诊断">
                        {orderedDiagnostics.map((diagnostic) => (
                            <li key={`${diagnostic.file}:${diagnostic.line}:${diagnostic.column}:${diagnostic.code}`}>
                                {formatDiagnostic(diagnostic)}
                            </li>
                        ))}
                    </ol>
                )}
            </section>
        </section>
    );
}
