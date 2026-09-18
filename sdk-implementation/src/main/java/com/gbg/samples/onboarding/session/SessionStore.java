package com.gbg.samples.onboarding.session;

import java.util.Optional;

public interface SessionStore {
    void save(Session session);

    Optional<Session> find(String sessionId);

    void remove(String sessionId);
}
