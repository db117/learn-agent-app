import type {WorkspaceFileEntry} from "../workspace/workspaceApi";

export type PracticeCheckSummary = {
    attemptId: number;
    compilePassed: boolean;
    testsPassed: boolean;
    testCount: number;
    verified: boolean;
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
    verifying?: boolean;
    feedback?: string | null;
    onSelectFile: (path: string) => void;
    onContentChange: (content: string) => void;
    onSave: () => void | Promise<void>;
    onCreateFile: (path: string) => void | Promise<void>;
    onVerify?: () => void | Promise<void>;
    theme?: "dark" | "light";
    fullscreen?: boolean;
    onToggleFullscreen?: () => void;
};
