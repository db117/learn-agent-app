export type LearningOutlineUnit = {
    title: string;
    objective: string;
};

export type LearningOutlineChapter = {
    title: string;
    units: LearningOutlineUnit[];
};

export type LearningOutline = {
    chapters: LearningOutlineChapter[];
    unitCount: number;
};

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonBlankText(value: unknown): value is string {
    return typeof value === "string" && value.trim().length > 0;
}

export function parseLearningOutline(text: string): LearningOutline | null {
    try {
        const value: unknown = JSON.parse(text);
        if (!isRecord(value) || !Array.isArray(value.chapters)
            || value.chapters.length === 0 || value.chapters.length > 50) return null;

        let unitCount = 0;
        const chapters: LearningOutlineChapter[] = [];
        for (const chapterValue of value.chapters) {
            if (!isRecord(chapterValue) || !isNonBlankText(chapterValue.code)
                || !isNonBlankText(chapterValue.title) || !Array.isArray(chapterValue.units)
                || chapterValue.units.length === 0 || chapterValue.units.length > 50) return null;

            const units: LearningOutlineUnit[] = [];
            for (const unitValue of chapterValue.units) {
                if (!isRecord(unitValue) || !isNonBlankText(unitValue.code)
                    || !isNonBlankText(unitValue.title) || !isNonBlankText(unitValue.objective)) return null;
                units.push({title: unitValue.title.trim(), objective: unitValue.objective.trim()});
                unitCount += 1;
                if (unitCount > 50) return null;
            }
            chapters.push({title: chapterValue.title.trim(), units});
        }
        return {chapters, unitCount};
    } catch {
        return null;
    }
}
