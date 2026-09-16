import type {WorkspaceFileEntry} from "./workspaceApi";

export type FileTreeNode = {
    name: string;
    path: string;
    kind: "directory" | "file";
    children?: FileTreeNode[];
};

export function buildFileTree(files: WorkspaceFileEntry[]): FileTreeNode {
    const root: FileTreeNode = {name: "", path: "", kind: "directory", children: []};
    for (const file of files) {
        const parts = file.path.split("/").filter(Boolean);
        let current = root;
        for (const [index, name] of parts.entries()) {
            const path = parts.slice(0, index + 1).join("/");
            const children = current.children ?? (current.children = []);
            let child = children.find((node) => node.name === name);
            if (!child) {
                child = {name, path, kind: index === parts.length - 1 ? "file" : "directory"};
                if (child.kind === "directory") child.children = [];
                children.push(child);
            }
            current = child;
        }
    }
    sortTree(root);
    return root;
}

function sortTree(node: FileTreeNode) {
    node.children?.sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
    node.children?.forEach(sortTree);
}
