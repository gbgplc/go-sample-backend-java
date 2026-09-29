package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.AppConfigResponse;
import com.gbg.samples.onboarding.api.dto.AttachmentResponse;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StartSessionResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.go.GoClient;
import com.gbg.samples.onboarding.go.GoStartResult;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates one onboarding session: mints it against Go, tracks the
 * session-to-instance mapping, enforces the cookie check and interaction
 * idempotency, and forwards everything else straight to {@link GoClient}.
 * Deliberately thin — the journey itself lives in Go (front-end handoff,
 * section 1, "division of responsibility").
 */
@Service
public class SessionService {

    private final GoClient goClient;
    private final SessionStore sessionStore;
    private final AppConfigProperties appConfig;

    public SessionService(GoClient goClient, SessionStore sessionStore, AppConfigProperties appConfig) {
        this.goClient = goClient;
        this.sessionStore = sessionStore;
        this.appConfig = appConfig;
    }

    public record Started(StartSessionResponse body, String cookieToken) {
    }

    public Started startSession(Map<String, Object> prefill, String scenarioHint) {
        GoStartResult result = goClient.startJourney(appConfig.resourceId(), prefill, scenarioHint);
        String sessionId = UUID.randomUUID().toString();
        String cookieToken = UUID.randomUUID().toString();
        String initialInteractionId = result.interaction() == null ? null : result.interaction().interactionId();

        Session session = new Session(sessionId, cookieToken, result.instanceId(), initialInteractionId);
        sessionStore.save(session);

        return new Started(new StartSessionResponse(sessionId, result.status(), result.interaction()), cookieToken);
    }

    public Interaction getInteraction(String sessionId, String cookieToken) {
        Session session = authorize(sessionId, cookieToken);
        // Locked the same as submitInteraction: GoApiClient's live-mode capture
        // disambiguation caches the outstanding-elements list per Go instance,
        // keyed off calls exactly like this one. Left unlocked, a poll here
        // could interleave with a concurrent submit's read of that cache and
        // hand it a snapshot from the wrong moment — serializing per session
        // closes that race the same way it closes the idempotency one.
        synchronized (session) {
            return goClient.fetchInteraction(session.goInstanceId()).interaction();
        }
    }

    public SubmitInteractionResponse submitInteraction(String sessionId, String cookieToken, String interactionId, Map<String, Object> data) {
        Session session = authorize(sessionId, cookieToken);

        // Locked per-session so two near-simultaneous identical submits (a
        // double-tap, a client retry) can't both pass the retry check before
        // either records the advance — without this, both would reach
        // goClient.submitInteraction, which is exactly what the idempotency
        // check exists to prevent.
        synchronized (session) {
            // Fetched once, up front — both the retry check below (which
            // needs the stage to tell two data-less screens apart, see
            // Session.isRetryOf) and requireCollectedFields need the current
            // interaction, and this is the only Go call among the two.
            Interaction current = goClient.fetchInteraction(session.goInstanceId()).interaction();
            String stage = current == null ? null : current.stage();

            if (session.isRetryOf(interactionId, stage, data)) {
                return session.cachedResponse();
            }
            if (session.currentInteractionId() != null && !session.currentInteractionId().equals(interactionId)) {
                throw OnboardingException.interactionStale("This step has moved on. Refetching the current one.");
            }

            requireCollectedFields(current, data);

            SubmitInteractionResponse response = goClient.submitInteraction(session.goInstanceId(), interactionId, data);
            session.recordAdvance(interactionId, stage, data, response);
            return response;
        }
    }

    /**
     * Rejects a submission that omits a field the current screen marks required.
     *
     * Go accepts such a submission: the journey advances, the domain element is
     * never populated, and the failure surfaces several screens later as
     * "Required domain element 'CurrentAddress' data is missing from context" —
     * naming an element the caller has already moved past, on a step they
     * cannot return to. A 422 here names the fields instead, while the caller
     * is still on the screen that collects them.
     *
     * The browser clients check this too, so in practice this catches the other
     * kind of caller: someone writing their own client against this API, who
     * would otherwise meet that 400 with nothing to act on.
     *
     * {@code required} is a nullable Boolean — the mock's fixtures leave it
     * unset, and a live journey's {@code collects} sets it explicitly. Only an
     * explicit true blocks, so an unknown requirement is never invented. A
     * value of whitespace alone is not an answer.
     */
    private void requireCollectedFields(Interaction current, Map<String, Object> data) {
        if (current == null || current.collects() == null) {
            return;
        }
        Map<String, Object> submitted = data == null ? Map.of() : data;
        Map<String, String> missing = new java.util.LinkedHashMap<>();
        current.collects().stream()
                .filter(field -> Boolean.TRUE.equals(field.required()))
                .filter(field -> isBlank(submitted.get(field.name())))
                .forEach(field -> missing.put(field.name(), "This is required."));

        if (!missing.isEmpty()) {
            throw OnboardingException.validationFailed("One or more required fields are missing.", missing);
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    public StateResponse getState(String sessionId, String cookieToken) {
        Session session = authorize(sessionId, cookieToken);
        return goClient.fetchState(session.goInstanceId());
    }

    public RecordResponse getRecord(String sessionId, String cookieToken) {
        Session session = authorize(sessionId, cookieToken);
        return goClient.fetchRecord(session.goInstanceId());
    }

    /**
     * Returns the captured image as base64, which the front end then submits
     * verbatim as its {@code attachmentRef}.
     *
     * Go has no attachment-upload endpoint to proxy to: document and selfie
     * images travel inside the interaction submission itself, base64-encoded
     * under {@code context.subject.documents[]} and
     * {@code context.subject.biometrics[]}. So "upload" here is an encode, and
     * the reference the front end holds onto <em>is</em> the payload —
     * {@code GoInteractionSubmitRequest} places it at the right schema path
     * when the capture screen submits.
     *
     * This previously synthesised an opaque reference and dropped the bytes,
     * which left the capture screens unable to complete against a live journey:
     * Go was sent a made-up string where it expected an image.
     */
    public AttachmentResponse uploadAttachment(String sessionId, String cookieToken, byte[] content) {
        authorize(sessionId, cookieToken);
        if (content == null || content.length == 0) {
            throw OnboardingException.validationFailed("That image could not be read. Try again.", null);
        }
        return new AttachmentResponse(Base64.getEncoder().encodeToString(content));
    }

    public AppConfigResponse getConfig() {
        return new AppConfigResponse(
                appConfig.brand(), appConfig.mark(), appConfig.tagline(), appConfig.accent(),
                appConfig.accentSoft(), appConfig.helpLine(), appConfig.journeyName(), appConfig.resourceId()
        );
    }

    private Session authorize(String sessionId, String cookieToken) {
        Session session = sessionStore.find(sessionId)
                .orElseThrow(() -> OnboardingException.sessionExpired("Your session has ended. Start again to continue."));
        if (cookieToken == null || !cookieToken.equals(session.cookieToken())) {
            throw OnboardingException.sessionExpired("Your session has ended. Start again to continue.");
        }
        return session;
    }
}
