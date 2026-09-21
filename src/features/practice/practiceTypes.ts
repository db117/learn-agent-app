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

export type ChoiceOption = {
    id: string;
    label: string;
};

export type ChoiceQuestion = {
    taskId: number;
    title: string;
    prompt: string;
    options: readonly ChoiceOption[];
};

export type PracticePanelProps = {
    files: readonly WorkspaceFileEntry[];
    selectedPath: string | null;
    content: string;
    loading?: boolean;
    loadingContent?: boolean;
    creating?: boolean;
    dirty?: boolean;
    saving?: boolean;
    compiling?: boolean;
    testing?: boolean;
    verifying?: boolean;
    practiceVerified?: boolean;
    codeVerified?: boolean;
    choiceQuestion?: ChoiceQuestion | null;
    choiceLoading?: boolean;
    choiceSubmitting?: boolean;
    choiceFeedback?: string | null;
    selectedChoiceId?: string | null;
    feedback?: string | null;
    runtimeSummary?: string | null;
    diagnostics?: readonly PracticeDiagnostic[];
    onSelectFile: (path: string) => void;
    onContentChange: (content: string) => void;
    onSave: () => void | Promise<void>;
    onCompile: () => void | Promise<void>;
    onTest: () => void | Promise<void>;
    onCreateFile: (path: string) => void | Promise<void>;
    onVerify?: () => void | Promise<void>;
    onSelectChoice?: (optionId: string) => void;
    onVerifyChoice?: () => void | Promise<void>;
    theme?: "dark" | "light";
    fullscreen?: boolean;
    onToggleFullscreen?: () => void;
};
