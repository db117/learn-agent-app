package com.db117.learnagent;

import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyStatus;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyBootstrapDomainTest {
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void newJourneyStoresGoalAndCanAttachOneGeneratedPath() {
        Journey journey = Journey.create(1, "掌握 Java 并完成一个后端项目", T0);

        assertNull(journey.id());
        assertEquals(JourneyStatus.ACTIVE, journey.status());
        assertEquals("掌握 Java 并完成一个后端项目", journey.goalDescription());
        assertFalse(journey.current());
        assertNull(journey.learningJourneyId());

        Journey selected = journey.withId(10).select().attachLearningJourney(20);
        assertTrue(selected.current());
        assertEquals(20L, selected.learningJourneyId());
    }

    @Test
    void archivedJourneyCannotBeSelectedOrRemainCurrent() {
        Journey selected = Journey.create(1, "Learn Java", T0).withId(10).select();

        assertThrows(DomainRuleViolation.class,
                () -> selected.archive(T0.plusSeconds(1)));
        Journey archived = selected.deselect().archive(T0.plusSeconds(1));
        assertEquals(JourneyStatus.ARCHIVED, archived.status());
        assertFalse(archived.current());
        assertThrows(DomainRuleViolation.class, archived::select);
    }
}
