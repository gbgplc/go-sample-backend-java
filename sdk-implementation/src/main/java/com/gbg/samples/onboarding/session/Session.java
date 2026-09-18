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
    /** The payload of the last submit — see {@link #isRetryOf}. */
    private volatile java.util.Map<String, Object> lastSubmittedData;
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

    /** Records a successful advance so a retried submit of the same step can be answered from cache. */
    public void recordAdvance(String submittedInteractionId, java.util.Map<String, Object> submittedData,
                              SubmitInteractionResponse response) {
        this.lastSubmittedInteractionId = submittedInteractionId;
        this.lastSubmittedData = submittedData;
        this.lastSubmittedResponse = response;
        this.currentInteractionId = response.interaction() == null ? null : response.interaction().interactionId();
    }

    /**
     * Whether this submit repeats the one just made.
     *
     * The interactionId alone cannot answer that against a live Go journey.
     * Go returns a single interaction — {@code segment1@latest} — for the whole
     * data-collection phase, and the id stays byte-identical from the first
     * screen to the last, so keying on it alone makes every step after the
     * first look like a retry of the one before and replays a stale cached
     * response instead of submitting. (Against the mock the ids differ per
     * step, which is why this only shows up live.)
     *
     * The stage the customer is being shown is what actually moves, so a true
     * retry is the same interactionId <em>and</em> the same stage still
     * standing. That keeps the double-submit protection the guard exists for
     * while letting a real advance through.
     */
    public boolean isRetryOf(String interactionId, java.util.Map<String, Object> data) {
        if (lastSubmittedInteractionId == null || !lastSubmittedInteractionId.equals(interactionId)) {
            return false;
        }
        // Same id and the same payload: a genuine double-submit — a
        // double-tapped button or a retried request. Same id with different
        // data is the next step, because Go reuses one interactionId for the
        // whole collection phase.
        return java.util.Objects.equals(lastSubmittedData, data);
    }

    public SubmitInteractionResponse cachedResponse() {
        return lastSubmittedResponse;
    }
}
