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
    /** The stage of the last submit — see {@link #isRetryOf}. */
    private volatile String lastSubmittedStage;
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
    public void recordAdvance(String submittedInteractionId, String submittedStage, java.util.Map<String, Object> submittedData,
                              SubmitInteractionResponse response) {
        this.lastSubmittedInteractionId = submittedInteractionId;
        this.lastSubmittedStage = submittedStage;
        this.lastSubmittedData = submittedData;
        this.lastSubmittedResponse = response;
        this.currentInteractionId = response.interaction() == null ? null : response.interaction().interactionId();
    }

    /**
     * Whether this submit repeats the one just made.
     *
     * Against the mock, interactionIds differ per step, so a submit whose id
     * matches {@code lastSubmittedInteractionId} is unambiguously a retry of
     * that exact step — comparing payload (the original approach) is enough,
     * and by the time this runs {@code currentInteractionId} has already
     * moved on to the next step's (different) id.
     *
     * Against a live Go journey the interactionId alone can't answer it: Go
     * returns a single interaction — {@code segment1@latest} — for the whole
     * data-collection phase, and the id stays byte-identical from the first
     * screen to the last, so {@code currentInteractionId} still equals
     * {@code lastSubmittedInteractionId} after a successful advance (unlike
     * the mock). That reused-id condition is the signal this method uses to
     * switch to comparing {@code stage} instead: two different, data-less
     * screens submitted back to back (e.g. an info screen then a consent
     * screen, both posting {@code {}}) have the same interactionId
     * <em>and</em> the same empty payload, so payload comparison alone
     * wrongly calls the second one a retry of the first and serves it the
     * first screen's cached response instead of forwarding it to Go — the
     * journey silently stalls on the consent screen the customer already
     * actioned. Stage tells them apart correctly, because it advances even
     * when the payload doesn't.
     *
     * <p>Stage alone isn't enough either, so the payload must match too: the
     * front and back of a two-sided document are two screens that
     * deliberately share one stage ("Document", so the progress rail doesn't
     * grow a step). Matching on stage alone called the back a retry of the
     * front and answered it from cache without ever reaching Go, which kept
     * asking for the back — an endless loop on that screen (live tenant,
     * 2026-09-29).
     *
     * <p>Note this can't also catch an immediate duplicate resubmission of
     * the very last live step (the two are indistinguishable once
     * {@code currentInteractionId} has moved past the stage that was
     * submitted — comparing a freshly-read stage back against
     * {@code lastSubmittedStage} would always disagree after a successful
     * advance, whether this is a genuine duplicate or the next screen).
     * Out of scope here: this method only fixes the false positive the
     * review reported, not live double-submit protection, which Go's own
     * handling of a repeated {@code segment1@latest} submission would need
     * to cover.
     *
     * <p>Falls back to the payload comparison whenever the reused-id
     * condition doesn't hold, or either side has no stage (the very first
     * submit, or a stage-less fixture).
     */
    public boolean isRetryOf(String interactionId, String stage, java.util.Map<String, Object> data) {
        if (lastSubmittedInteractionId == null || !lastSubmittedInteractionId.equals(interactionId)) {
            return false;
        }
        boolean samePayload = java.util.Objects.equals(lastSubmittedData, data);
        boolean interactionIdReusedAcrossStages = currentInteractionId != null && currentInteractionId.equals(interactionId);
        if (interactionIdReusedAcrossStages && stage != null && lastSubmittedStage != null) {
            return stage.equals(lastSubmittedStage) && samePayload;
        }
        return samePayload;
    }

    public SubmitInteractionResponse cachedResponse() {
        return lastSubmittedResponse;
    }
}
