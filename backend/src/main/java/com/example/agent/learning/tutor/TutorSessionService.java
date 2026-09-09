package com.example.agent.learning.tutor;

import com.example.agent.agent.TutorAgentService;
import com.example.agent.config.AppProperties;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Owns the durable Journey + LearnUnit to TutorSession association. */
@Service
public class TutorSessionService {

    private final LearningRepository learning;
    private final SqliteRepository sessions;
    private final TutorAgentService tutor;
    private final AppProperties properties;

    public TutorSessionService(
            LearningRepository learning,
            SqliteRepository sessions,
            TutorAgentService tutor,
            AppProperties properties) {
        this.learning = learning;
        this.sessions = sessions;
        this.tutor = tutor;
        this.properties = properties;
    }

    @Transactional
    public TutorSession open(String journeyId, String learnUnitCode) {
        LearnUnit learnUnit = learning.listLearnUnitsForJourney(journeyId).stream()
                .filter(value -> value.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit not found: " + learnUnitCode));

        String sessionId = learning.findTutorSessionId(journeyId, learnUnit.code()).orElse(null);
        if (sessionId == null) {
            sessionId = stableSessionId(journeyId, learnUnit.code());
            Instant now = Instant.now();
            sessions.insertSessionIfAbsent(new SessionRecord(
                    sessionId, properties.userId(), "Tutor · " + learnUnit.code(), now, now));
            learning.linkTutorSession(journeyId, learnUnit.code(), sessionId);
            String linkedSessionId = learning.findTutorSessionId(journeyId, learnUnit.code())
                    .orElseThrow(() -> new IllegalStateException("TutorSession link was not saved"));
            sessionId = linkedSessionId;
        }

        String resolvedSessionId = sessionId;
        SessionRecord session = sessions.findSession(resolvedSessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "TutorSession link points to a missing session: " + resolvedSessionId));
        if (!properties.userId().equals(session.userId())) {
            throw new IllegalStateException("TutorSession belongs to another user");
        }
        tutor.ensureSession(session);
        return new TutorSession(session, journeyId, learnUnit.code());
    }

    private static String stableSessionId(String journeyId, String learnUnitCode) {
        return UUID.nameUUIDFromBytes(
                (journeyId + "\u0000" + learnUnitCode).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record TutorSession(SessionRecord session, String journeyId, String learnUnitCode) {
    }
}
