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
            if (session.isRetryOf(interactionId, data)) {
                return session.cachedResponse();
            }
            if (session.currentInteractionId() != null && !session.currentInteractionId().equals(interactionId)) {
                throw OnboardingException.interactionStale("This step has moved on. Refetching the current one.");
            }

            SubmitInteractionResponse response = goClient.submitInteraction(session.goInstanceId(), interactionId, data);
            session.recordAdvance(interactionId, data, response);
            return response;
        }
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
