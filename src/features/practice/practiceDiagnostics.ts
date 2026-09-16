import type {PracticeDiagnostic} from "./practiceTypes";

export function sortDiagnostics(diagnostics: readonly PracticeDiagnostic[]) {
    return [...diagnostics].sort((left, right) =>
        left.file.localeCompare(right.file)
        || left.line - right.line
        || left.column - right.column
        || left.severity.localeCompare(right.severity)
        || left.code.localeCompare(right.code),
    );
}

export function formatDiagnostic(diagnostic: PracticeDiagnostic) {
    return `${diagnostic.severity} · ${diagnostic.file}:${diagnostic.line}:${diagnostic.column} · `
        + `${diagnostic.code} · ${diagnostic.message}`;
}
