import {createElement} from "react";
import {renderToStaticMarkup} from "react-dom/server";
import {describe, expect, it} from "vitest";
import {MarkdownMessage} from "./MarkdownMessage";

describe("MarkdownMessage", () => {
    it("renders headings, tables, inline code, and fenced code without raw markdown", () => {
        const markup = renderToStaticMarkup(createElement(MarkdownMessage, {
            text: "# Concept\n\n|方面|Java|TypeScript|\n|---|---|---|\n|编译结果|`.class`|`.js`|\n\n```typescript\nconst score: number = 42;\n```",
        }));

        expect(markup).toContain("<h1>Concept</h1>");
        expect(markup).toContain("<table>");
        expect(markup).toContain("<code class=\"language-typescript\">const score: number = 42;</code>");
        expect(markup).not.toContain("|---|---|---|");
    });
});
