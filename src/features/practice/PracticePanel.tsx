import Editor from "@monaco-editor/react";
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
                                  dirty = false,
                                  saving = false,
                                  compiling = false,
                                  testing = false,
                                  feedback = null,
                                  diagnostics = [],
                                  onSelectFile,
                                  onContentChange,
                                  onSave,
                                  onCompile,
                                  onTest,
                              }: PracticePanelProps) {
    const tree = buildFileTree([...files]);
    const orderedDiagnostics = sortDiagnostics(diagnostics);
    const actionsDisabled = loading || loadingContent || saving;

    return (
        <section className="workspace-panel" aria-labelledby="practice-title">
            <div className="workspace-heading">
                <div>
                    <p className="mode-label">PRACTICE RUNTIME</p>
                    <h3 id="practice-title">代码练习</h3>
                </div>
                <div className="workspace-actions">
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
                </div>
            </div>

            {feedback && <p className="form-feedback" role="alert">{feedback}</p>}

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
                            height="360px"
                            language="typescript"
                            theme="vs-dark"
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
