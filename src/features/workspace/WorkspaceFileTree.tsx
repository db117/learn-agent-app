import type {FileTreeNode} from "./fileTree";

type Props = {
    tree: FileTreeNode;
    selectedPath: string | null;
    onSelect: (path: string) => void;
};

export function WorkspaceFileTree({tree, selectedPath, onSelect}: Props) {
    return <ul aria-label="Workspace files">{tree.children?.map((node) =>
        <TreeNode key={node.path} node={node} selectedPath={selectedPath} onSelect={onSelect}/>,
    )}</ul>;
}

function TreeNode({node, selectedPath, onSelect}: Omit<Props, "tree"> & { node: FileTreeNode }) {
    if (node.kind === "directory") {
        return <li><span>{node.name}</span>
            <ul>{node.children?.map((child) =>
                <TreeNode key={child.path} node={child} selectedPath={selectedPath} onSelect={onSelect}/>,
            )}</ul>
        </li>;
    }
    return <li>
        <button type="button" aria-pressed={selectedPath === node.path}
                onClick={() => onSelect(node.path)}>{node.name}</button>
    </li>;
}
