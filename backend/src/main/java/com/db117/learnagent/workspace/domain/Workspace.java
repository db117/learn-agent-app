package com.db117.learnagent.workspace.domain;

import java.nio.file.Path;

/** 只表达 Workspace 身份及其后端根路径，不承担 DTO 职责。 */
public class Workspace {
    private final WorkspaceReference reference;
    private final Path root;

    public Workspace(WorkspaceReference reference, Path root) {
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.reference = reference;
        this.root = root.toAbsolutePath().normalize();
    }

    public WorkspaceReference reference() {
        return reference;
    }

    public Path root() {
        return root;
    }
}
