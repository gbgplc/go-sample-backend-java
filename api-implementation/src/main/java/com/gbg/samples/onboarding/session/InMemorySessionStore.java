package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.config.SessionProperties;
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
        if (Duration.between(session.lastAccessedAt(), Instant.now()).compareTo(ttl) > 0) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        session.touch();
        return Optional.of(session);
    }

    @Override
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
