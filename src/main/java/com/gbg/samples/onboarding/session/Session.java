package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;

import java.time.Instant;

/**
 * The session-to-instance mapping (front-end handoff, section 1) — the one
 * thing this service is the source of truth for. Everything about the
 * journey itself lives in Go, addressed by {@code goInstanceId}.
 *
 * In-memory and single-node, which is the right amount of infrastructure for
 * a sample: swap for Redis (or any shared store) before running more than
 * one instance, since sessions would otherwise pin to whichever node started them.
 */
public final class Session {

    private final String id;
    private final String cookieToken;
    private final String goInstanceId;
    private final Instant createdAt;

    private volatile String currentInteractionId;
    private volatile String lastSubmittedInteractionId;
    private volatile SubmitInteractionResponse lastSubmittedResponse;
    private volatile Instant lastAccessedAt;

    public Session(String id, String cookieToken, String goInstanceId, String initialInteractionId) {
        this.id = id;
        this.cookieToken = cookieToken;
        this.goInstanceId = goInstanceId;
        this.currentInteractionId = initialInteractionId;
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
    }

    public String id() {
        return id;
    }

    public String cookieToken() {
        return cookieToken;
    }

    public String goInstanceId() {
        return goInstanceId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }

    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    public String currentInteractionId() {
        return currentInteractionId;
    }

    /** Records a successful advance so a retried submit of the same interactionId can be answered from cache. */
    public void recordAdvance(String submittedInteractionId, SubmitInteractionResponse response) {
        this.lastSubmittedInteractionId = submittedInteractionId;
        this.lastSubmittedResponse = response;
        this.currentInteractionId = response.interaction() == null ? null : response.interaction().interactionId();
    }

    public boolean isRetryOf(String interactionId) {
        return lastSubmittedInteractionId != null && lastSubmittedInteractionId.equals(interactionId);
    }

    public SubmitInteractionResponse cachedResponse() {
        return lastSubmittedResponse;
    }
}
