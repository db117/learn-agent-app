import type {WorkspaceFileEntry} from "../workspace/workspaceApi";

export type PracticeDiagnosticSeverity = "ERROR" | "WARNING" | "INFO";

export type PracticeDiagnostic = {
    file: string;
    line: number;
    column: number;
    code: string;
    severity: PracticeDiagnosticSeverity;
    message: string;
};

export type PracticePanelProps = {
    files: readonly WorkspaceFileEntry[];
    selectedPath: string | null;
    content: string;
    loading?: boolean;
    loadingContent?: boolean;
    dirty?: boolean;
    saving?: boolean;
    compiling?: boolean;
    testing?: boolean;
    verifying?: boolean;
    practiceVerified?: boolean;
    feedback?: string | null;
    runtimeSummary?: string | null;
    diagnostics?: readonly PracticeDiagnostic[];
    onSelectFile: (path: string) => void;
    onContentChange: (content: string) => void;
    onSave: () => void | Promise<void>;
    onCompile: () => void | Promise<void>;
    onTest: () => void | Promise<void>;
    onVerify?: () => void | Promise<void>;
};
