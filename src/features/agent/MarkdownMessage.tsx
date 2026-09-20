import type {ReactNode} from "react";

type Props = { text: string };

type Block =
    | { kind: "code"; language: string; content: string }
    | { kind: "heading"; level: number; content: string }
    | { kind: "paragraph"; content: string }
    | { kind: "list"; ordered: boolean; items: string[] }
    | { kind: "table"; headers: string[]; rows: string[][] };

const INLINE_TOKEN = /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`)/g;

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

        const fence = line.match(/^\s*```\s*([\w-]*)\s*$/);
        if (fence) {
            const content: string[] = [];
            index++;
            while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) {
                content.push(lines[index++]);
            }
            if (index < lines.length) index++;
            blocks.push({kind: "code", language: fence[1], content: content.join("\n")});
            continue;
        }

        const heading = line.match(/^(#{1,6})\s+(.+)$/);
        if (heading) {
            blocks.push({kind: "heading", level: heading[1].length, content: heading[2]});
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
        && !/^\s*```\s*[\w-]*\s*$/.test(lines[index])
        && !/^#{1,6}\s+/.test(lines[index])
        && !/^\s*([-*+]\s+|\d+[.)]\s+)/.test(lines[index])) {
            paragraph.push(lines[index++]);
        }
        blocks.push({kind: "paragraph", content: paragraph.join("\n")});
    }
    return blocks;
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
