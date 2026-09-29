package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.config.SessionProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public InMemorySessionStore(SessionProperties properties) {
        this.ttl = Duration.ofMinutes(properties.ttlMinutes());
    }

    @Override
    public void save(Session session) {
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<Session> find(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (expired(session, Instant.now())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        session.touch();
        return Optional.of(session);
    }

    /**
     * Drops sessions nobody has come back for. {@link #find} only evicts the
     * one it's asked about, so a session that is never looked up again — an
     * abandoned tab, or a loop of unauthenticated {@code POST /v1/sessions} —
     * would otherwise stay in memory for the life of the process.
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void evictExpired() {
        Instant now = Instant.now();
        sessions.values().removeIf(session -> expired(session, now));
    }

    int size() {
        return sessions.size();
    }

    private boolean expired(Session session, Instant now) {
        return Duration.between(session.lastAccessedAt(), now).compareTo(ttl) > 0;
    }

    @Override
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
