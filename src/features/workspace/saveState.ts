export type SaveState = {
    selectedPath: string | null;
    loadedContent: string | null;
    draftContent: string;
    dirty: boolean;
    saving: boolean;
    error: string | null;
};

export function initialSaveState(): SaveState {
    return {selectedPath: null, loadedContent: null, draftContent: "", dirty: false, saving: false, error: null};
}

export function selectFile(path: string, content: string): SaveState {
    return {
        selectedPath: path,
        loadedContent: content,
        draftContent: content,
        dirty: false,
        saving: false,
        error: null
    };
}

export function editDraft(state: SaveState, draftContent: string): SaveState {
    return {...state, draftContent, dirty: draftContent !== state.loadedContent, error: null};
}

export function beginSave(state: SaveState): SaveState {
    return {...state, saving: true, error: null};
}

export function finishSave(state: SaveState, content = state.draftContent): SaveState {
    return {...state, loadedContent: content, draftContent: content, dirty: false, saving: false, error: null};
}

export function failSave(state: SaveState, error: string): SaveState {
    return {...state, saving: false, error};
}
