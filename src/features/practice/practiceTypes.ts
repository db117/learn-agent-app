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
    dirty?: boolean;
    saving?: boolean;
    compiling?: boolean;
    testing?: boolean;
    verifying?: boolean;
    practiceVerified?: boolean;
    feedback?: string | null;
    runtimeSummary?: string | null;
    diagnostics?: readonly PracticeDiagnostic[];
    choiceQuestion?: ChoiceQuestion | null;
    choiceLoading?: boolean;
    choiceSubmitting?: boolean;
    selectedChoiceId?: string | null;
    onSelectFile: (path: string) => void;
    onContentChange: (content: string) => void;
    onSave: () => void | Promise<void>;
    onCompile: () => void | Promise<void>;
    onTest: () => void | Promise<void>;
    onVerify?: () => void | Promise<void>;
    onStartChoice?: () => void | Promise<void>;
    onSelectChoice?: (optionId: string) => void;
    onSubmitChoice?: () => void | Promise<void>;
};
