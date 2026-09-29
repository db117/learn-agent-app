import {createElement} from "react";
import {renderToStaticMarkup} from "react-dom/server";
import {describe, expect, it, vi} from "vitest";
import {PracticePanel} from "./PracticePanel";
import type {PracticePanelProps} from "./practiceTypes";

function panelProps(overrides: Partial<PracticePanelProps> = {}): PracticePanelProps {
    return {
        files: [{path: "src/main.ts", size: 12, modifiedAt: "now"}],
        selectedPath: "src/main.ts",
        content: "export const answer = 42;",
        onSelectFile: vi.fn(),
        onContentChange: vi.fn(),
        onSave: vi.fn(),
        onCreateFile: vi.fn(),
        ...overrides,
    };
}

describe("PracticePanel", () => {
    it("keeps the editable workspace and removes standalone compile and test actions", () => {
        const markup = renderToStaticMarkup(createElement(PracticePanel, panelProps()));

        expect(markup).toContain("src/main.ts");
        expect(markup).toContain("保存");
        expect(markup).toContain("新建文件");
        expect(markup).not.toContain("编译");
        expect(markup).not.toContain("测试");
    });
});
