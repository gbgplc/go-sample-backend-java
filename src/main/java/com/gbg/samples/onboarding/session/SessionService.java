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
        return goClient.fetchInteraction(session.goInstanceId()).interaction();
    }

    public SubmitInteractionResponse submitInteraction(String sessionId, String cookieToken, String interactionId, Map<String, Object> data) {
        Session session = authorize(sessionId, cookieToken);

        if (session.isRetryOf(interactionId)) {
            return session.cachedResponse();
        }
        if (session.currentInteractionId() != null && !session.currentInteractionId().equals(interactionId)) {
            throw OnboardingException.interactionStale("This step has moved on. Refetching the current one.");
        }

        SubmitInteractionResponse response = goClient.submitInteraction(session.goInstanceId(), interactionId, data);
        session.recordAdvance(interactionId, response);
        return response;
    }

    public StateResponse getState(String sessionId, String cookieToken) {
        Session session = authorize(sessionId, cookieToken);
        return goClient.fetchState(session.goInstanceId());
    }

    public RecordResponse getRecord(String sessionId, String cookieToken) {
        Session session = authorize(sessionId, cookieToken);
        return goClient.fetchRecord(session.goInstanceId());
    }

    public AttachmentResponse uploadAttachment(String sessionId, String cookieToken, String originalFilename) {
        authorize(sessionId, cookieToken);
        // Document/selfie capture stays a placeholder until a capture SDK is
        // chosen (front-end handoff, section 6) — this synthesises a reference
        // rather than proxying to a real Go attachments endpoint.
        String safeName = originalFilename == null ? "file" : originalFilename;
        return new AttachmentResponse("attachment_" + sessionId + "_" + UUID.randomUUID() + "_" + safeName);
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
