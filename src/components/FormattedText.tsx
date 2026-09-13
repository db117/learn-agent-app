import type {ReactNode} from "react";

type Block =
    | { kind: "paragraph"; lines: string[] }
    | { kind: "list"; ordered: boolean; items: string[] }
    | { kind: "code"; lines: string[] };

function blocks(text: string): Block[] {
    const result: Block[] = [];
    const lines = text.replace(/\r\n?/g, "\n").split("\n");
    let paragraph: string[] = [];
    let list: Extract<Block, { kind: "list" }> | null = null;
    let code: string[] | null = null;

    const flush = () => {
        if (paragraph.length) result.push({kind: "paragraph", lines: paragraph});
        paragraph = [];
        if (list) result.push(list);
        list = null;
    };

    for (const rawLine of lines) {
        const line = code ? rawLine : rawLine.replace(/^\s*>\s?/, "");
        if (code) {
            if (/^\s*```(?:[\w+#.-]+)?\s*$/.test(line)) {
                result.push({kind: "code", lines: code});
                code = null;
            } else {
                code.push(line);
            }
            continue;
        }
        if (/^\s*```(?:[\w+#.-]+)?\s*$/.test(line)) {
            flush();
            code = [];
            continue;
        }
        if (!line.trim()) {
            flush();
            continue;
        }
        const unordered = line.match(/^\s*[-*•]\s+(.+)$/);
        const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
        if (unordered || ordered) {
            const orderedList = Boolean(ordered);
            if (!list || list.ordered !== orderedList) {
                flush();
                list = {kind: "list", ordered: orderedList, items: []};
            }
            list.items.push((unordered ?? ordered)![1]);
            continue;
        }
        if (list) flush();
        paragraph.push(line);
    }
    if (code) result.push({kind: "code", lines: code});
    flush();
    return result;
}

function inline(text: string): ReactNode[] {
    return text.split(/(`[^`\n]+`|\*\*[^*\n]+\*\*)/g).map((part, index) => {
        if (part.startsWith("`") && part.endsWith("`")) {
            return <code key={index}>{part.slice(1, -1)}</code>;
        }
        if (part.startsWith("**") && part.endsWith("**")) {
            return <strong key={index}>{part.slice(2, -2)}</strong>;
        }
        return part;
    });
}

export function FormattedText({text, className = ""}: { text: string; className?: string }) {
    if (!text.trim()) return null;
    return <div className={`formatted-text ${className}`.trim()}>
        {blocks(text).map((block, index) => {
            if (block.kind === "code") {
                return <pre className="formatted-code" key={index}><code>{block.lines.join("\n")}</code></pre>;
            }
            if (block.kind === "list") {
                const List = block.ordered ? "ol" : "ul";
                return <List key={index}>{block.items.map((item, itemIndex) =>
                    <li key={itemIndex}>{inline(item)}</li>)}</List>;
            }
            return <p key={index}>{block.lines.map((line, lineIndex) =>
                <span key={lineIndex}>{line}{lineIndex < block.lines.length - 1 && <br/>}</span>)}</p>;
        })}
    </div>;
}
