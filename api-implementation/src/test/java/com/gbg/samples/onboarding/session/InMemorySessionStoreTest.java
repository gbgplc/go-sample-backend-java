package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.config.SessionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Review finding 8: sessions nobody looks up again are swept, not kept forever. */
class InMemorySessionStoreTest {

    @Test
    void theSweepRemovesExpiredSessionsNobodyLookedUpAgain() throws InterruptedException {
        InMemorySessionStore store = new InMemorySessionStore(new SessionProperties(0, "onboarding_session"));
        store.save(new Session("s1", "cookie", "instance-1", "int-1"));
        store.save(new Session("s2", "cookie", "instance-2", "int-1"));
        Thread.sleep(5);

        store.evictExpired();

        assertThat(store.size()).isZero();
    }

    @Test
    void theSweepKeepsLiveSessions() {
        InMemorySessionStore store = new InMemorySessionStore(new SessionProperties(30, "onboarding_session"));
        store.save(new Session("s1", "cookie", "instance-1", "int-1"));

        store.evictExpired();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.find("s1")).isPresent();
    }
}
