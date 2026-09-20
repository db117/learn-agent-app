import {createElement} from "react";
import {renderToStaticMarkup} from "react-dom/server";
import {describe, expect, it, vi} from "vitest";
import {PracticePanel} from "./PracticePanel";
import {formatDiagnostic, sortDiagnostics} from "./practiceDiagnostics";
import type {PracticeDiagnostic, PracticePanelProps} from "./practiceTypes";

const diagnostics: PracticeDiagnostic[] = [
    {
        file: "src/main.ts",
        line: 4,
        column: 2,
        code: "TS2322",
        severity: "ERROR",
        message: "Type 'string' is not assignable to type 'number'.",
    },
    {
        file: "src/main.ts",
        line: 2,
        column: 1,
        code: "TS6133",
        severity: "WARNING",
        message: "变量已声明但从未读取。",
    },
];

function panelProps(overrides: Partial<PracticePanelProps> = {}): PracticePanelProps {
    return {
        files: [{path: "src/main.ts", size: 12, modifiedAt: "now"}],
        selectedPath: null,
        content: "",
        onSelectFile: vi.fn(),
        onContentChange: vi.fn(),
        onSave: vi.fn(),
        onCompile: vi.fn(),
        onTest: vi.fn(),
        onCreateFile: vi.fn(),
        ...overrides,
    };
}

describe("practice diagnostics", () => {
    it("sorts without mutating input and formats source locations", () => {
        const ordered = sortDiagnostics(diagnostics);
        expect(ordered.map((diagnostic) => diagnostic.line)).toEqual([2, 4]);
        expect(diagnostics[0].line).toBe(4);
        expect(formatDiagnostic(ordered[0])).toContain("src/main.ts:2:1");
        expect(formatDiagnostic(ordered[0])).toContain("TS6133");
    });
});

describe("PracticePanel", () => {
    it("renders workspace actions, file tree and diagnostics from props", () => {
        const markup = renderToStaticMarkup(createElement(PracticePanel, panelProps({
            diagnostics,
            runtimeSummary: "failed to start COMPILE: pnpm.cmd",
        })));
        expect(markup).toContain("编译");
        expect(markup).toContain("测试");
        expect(markup).toContain("保存");
        expect(markup).toContain("新建文件");
        expect(markup).toContain("main.ts");
        expect(markup).toContain("src/main.ts:2:1");
        expect(markup).toContain("TS2322");
        expect(markup).toContain("failed to start COMPILE: pnpm.cmd");
    });

    it("renders a choice question without exposing an answer field", () => {
        const markup = renderToStaticMarkup(createElement(PracticePanel, panelProps({
            choiceQuestion: {
                taskId: 7,
                title: "选择题：变量",
                prompt: "下列哪项符合本单元目标？",
                options: [
                    {id: "objective", label: "理解变量类型"},
                    {id: "distractor", label: "配置数据库"},
                ],
            },
            selectedChoiceId: "objective",
        })));
        expect(markup).toContain("开始选择题");
        expect(markup).toContain("下列哪项符合本单元目标？");
        expect(markup).toContain("理解变量类型");
        expect(markup).not.toContain("correctOptionId");
    });
});
