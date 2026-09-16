package com.db117.learnagent.agent;

import com.db117.learnagent.agent.domain.WorkspaceBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceBindingTest {
    @Test
    void keepsPlanningAndLearningWorkspaceIdentitiesSeparate() {
        assertEquals(new WorkspaceBinding("AGENT", "agent"), WorkspaceBinding.agent());
        assertEquals(new WorkspaceBinding("LEARNING", "7"), WorkspaceBinding.learning(7));
    }
}
