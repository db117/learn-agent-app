import {createElement} from "react";
import {renderToStaticMarkup} from "react-dom/server";
import {describe, expect, it} from "vitest";
import {type LearningProgress, LearnModePanel} from "./LearnModePanel";

const progress: LearningProgress = {
    journeyId: 1,
    learningJourneyId: 2,
    status: "ACTIVE",
    currentLearnUnitCode: "first-lesson",
    currentLearnUnitTitle: "第 1 单元：变量",
    currentLearnUnitObjective: "理解 TypeScript 变量",
    currentLearnUnitContent: "## Concept\n变量保存值。\n\n## Example\n```typescript\nconst answer = 42;\n```",
    currentMasteryScore: 0,
    currentBestScore: 0,
    practiceVerified: false,
    completedCount: 0,
    totalCount: 1,
    chapters: [{
        code: "basics",
        title: "基础",
        units: [{code: "first-lesson", title: "第 1 单元：变量", status: "CURRENT", practiceVerified: false}],
    }],
};

describe("LearnModePanel", () => {
    it("renders the lesson content and learning stages", () => {
        const markup = renderToStaticMarkup(createElement(LearnModePanel, {progress}));

        expect(markup).toContain("LEARN MODE");
        expect(markup).toContain("理解 TypeScript 变量");
        expect(markup).toContain("## Concept");
        expect(markup).toContain("## Example");
        expect(markup).toContain("Practice");
        expect(markup).not.toContain("Assessment");
    });

    it("marks Practice after its evidence passes", () => {
        const markup = renderToStaticMarkup(createElement(LearnModePanel, {
            progress: {...progress, practiceVerified: true},
        }));

        expect((markup.match(/class="completed"/g) ?? []).length).toBe(1);
    });
});
