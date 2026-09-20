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

    it("tolerates compact headings and a fence whose first code line follows the language", () => {
        const markup = renderToStaticMarkup(createElement(MarkdownMessage, {
            text: 'Concept: 变量\n\n###1. 基础类型\n\n```tslet username: string = "Ada";\nlet age: number = 18;\n```\n\n与 Java 对比：\n\n- TypeScript 的 `string` 类似 Java 的 `String`。',
        }));

        expect(markup).toContain("<h3>1. 基础类型</h3>");
        expect(markup).toContain("<code class=\"language-ts\">let username: string = &quot;Ada&quot;;\nlet age: number = 18;</code>");
        expect(markup).toContain("<p>与 Java 对比：</p>");
        expect(markup).toContain("<ul>");
        expect(markup).not.toContain("###1.");
        expect(markup).not.toContain("```ts");
    });
});
