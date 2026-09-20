import type {ReactNode} from "react";

type Props = { text: string };

type Block =
    | { kind: "code"; language: string; content: string }
    | { kind: "heading"; level: number; content: string }
    | { kind: "paragraph"; content: string }
    | { kind: "list"; ordered: boolean; items: string[] }
    | { kind: "table"; headers: string[]; rows: string[][] };

type Heading = { level: number; content: string };
type Fence = { language: string; firstLine: string };

const INLINE_TOKEN = /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`)/g;
const FENCE_LANGUAGES = [
    "typescript", "javascript", "markdown", "python", "java", "json", "shell", "bash",
    "html", "css", "sql", "tsx", "jsx", "md", "ts", "js",
];

export function MarkdownMessage({text}: Props) {
    return <div className="markdown-message">
        {parseBlocks(text).map((block, index) => renderBlock(block, index))}
    </div>;
}

function parseBlocks(markdown: string): Block[] {
    const lines = markdown.replaceAll("\r\n", "\n").split("\n");
    const blocks: Block[] = [];
    let index = 0;
    while (index < lines.length) {
        const line = lines[index];
        if (!line.trim()) {
            index++;
            continue;
        }

        if (isClosingFence(line)) {
            index++;
            continue;
        }

        const fence = parseFence(line);
        if (fence) {
            const content: string[] = fence.firstLine ? [fence.firstLine] : [];
            index++;
            while (index < lines.length && !isClosingFence(lines[index])) {
                content.push(lines[index++]);
            }
            if (index < lines.length) index++;
            blocks.push({kind: "code", language: fence.language, content: content.join("\n")});
            continue;
        }

        const heading = parseHeading(line);
        if (heading) {
            blocks.push({kind: "heading", level: heading.level, content: heading.content});
            index++;
            continue;
        }

        if (index + 1 < lines.length && line.includes("|") && isTableSeparator(lines[index + 1])) {
            const headers = tableCells(line);
            index += 2;
            const rows: string[][] = [];
            while (index < lines.length && lines[index].includes("|") && lines[index].trim()) {
                rows.push(tableCells(lines[index++]));
            }
            blocks.push({kind: "table", headers, rows});
            continue;
        }

        const listItem = line.match(/^\s*([-*+]\s+|\d+[.)]\s+)(.+)$/);
        if (listItem) {
            const ordered = /^\d/.test(listItem[1]);
            const items = [listItem[2]];
            index++;
            while (index < lines.length) {
                const next = lines[index].match(/^\s*([-*+]\s+|\d+[.)]\s+)(.+)$/);
                if (!next || /^\d/.test(next[1]) !== ordered) break;
                items.push(next[2]);
                index++;
            }
            blocks.push({kind: "list", ordered, items});
            continue;
        }

        const paragraph = [line];
        index++;
        while (index < lines.length && lines[index].trim()
        && !parseFence(lines[index])
        && !parseHeading(lines[index])
        && !/^\s*([-*+]\s+|\d+[.)]\s+)/.test(lines[index])) {
            paragraph.push(lines[index++]);
        }
        blocks.push({kind: "paragraph", content: paragraph.join("\n")});
    }
    return blocks;
}

function parseHeading(line: string): Heading | null {
    // 容忍模型常见的 `###1.` 写法，同时保留标准 Markdown 标题。
    const match = line.match(/^\s*(#{1,6})(?:\s+|(?=\S))(.+?)\s*$/);
    return match ? {level: match[1].length, content: match[2]} : null;
}

function parseFence(line: string): Fence | null {
    const match = line.match(/^\s*```(.*)$/);
    if (!match) return null;

    const value = match[1];
    const lowerValue = value.toLowerCase();
    const language = FENCE_LANGUAGES.find((candidate) => lowerValue.startsWith(candidate));
    if (language) {
        return {language, firstLine: value.slice(language.length).trimStart()};
    }

    const standardLanguage = value.match(/^(\w[\w-]*)\s*$/);
    return {
        language: standardLanguage?.[1] ?? "",
        firstLine: standardLanguage ? "" : value.trimStart(),
    };
}

function isClosingFence(line: string): boolean {
    return /^\s*```\s*$/.test(line);
}

function renderBlock(block: Block, index: number): ReactNode {
    if (block.kind === "code") {
        return <pre key={index}><code
            className={block.language ? `language-${block.language}` : undefined}>{block.content}</code></pre>;
    }
    if (block.kind === "heading") {
        const Heading = `h${block.level}` as "h1" | "h2" | "h3" | "h4" | "h5" | "h6";
        return <Heading key={index}>{renderInline(block.content)}</Heading>;
    }
    if (block.kind === "table") {
        return <table key={index}>
            <thead>
            <tr>{block.headers.map((header, cellIndex) => <th key={cellIndex}>{renderInline(header)}</th>)}</tr>
            </thead>
            <tbody>{block.rows.map((row, rowIndex) => <tr key={rowIndex}>
                {block.headers.map((_, cellIndex) => <td key={cellIndex}>{renderInline(row[cellIndex] ?? "")}</td>)}
            </tr>)}</tbody>
        </table>;
    }
    if (block.kind === "list") {
        const List = block.ordered ? "ol" : "ul";
        return <List key={index}>{block.items.map((item, itemIndex) => <li
            key={itemIndex}>{renderInline(item)}</li>)}</List>;
    }
    return <p key={index}>{renderInline(block.content)}</p>;
}

function renderInline(text: string): ReactNode[] {
    return text.split(INLINE_TOKEN).map((part, index) => {
        if (part.startsWith("`") && part.endsWith("`")) {
            return <code key={index}>{part.slice(1, -1)}</code>;
        }
        if ((part.startsWith("**") && part.endsWith("**"))
            || (part.startsWith("__") && part.endsWith("__"))) {
            return <strong key={index}>{part.slice(2, -2)}</strong>;
        }
        return part;
    });
}

function isTableSeparator(line: string): boolean {
    const cells = tableCells(line);
    return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function tableCells(line: string): string[] {
    const trimmed = line.trim().replace(/^\|/, "").replace(/\|$/, "");
    return trimmed.split("|").map((cell) => cell.trim());
}
