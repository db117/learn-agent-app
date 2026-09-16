package com.db117.learnagent.workspace;

import com.db117.learnagent.workspace.domain.WorkspaceKind;
import com.db117.learnagent.workspace.domain.WorkspaceReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceReferenceTest {
    @Test
    void exposesStableExternalReferenceWithoutHostPath() {
        assertEquals("learning:7", new WorkspaceReference(WorkspaceKind.LEARNING, 7).externalForm());
        assertEquals("project:8", new WorkspaceReference(WorkspaceKind.PROJECT, 8).externalForm());
    }
}
