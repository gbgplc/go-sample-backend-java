package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.config.GoProperties;
import com.gbg.samples.onboarding.go.GoClient;
import com.gbg.samples.onboarding.go.GoStartResult;
import com.gbg.samples.onboarding.go.live.dto.GoErrorEnvelope;
import com.gbg.samples.onboarding.go.live.dto.GoInstanceRequest;
import com.gbg.samples.onboarding.go.live.dto.GoInteractionFetchResponse;
import com.gbg.samples.onboarding.go.live.dto.GoInteractionSubmitRequest;
import com.gbg.samples.onboarding.go.live.dto.GoStartRequest;
import com.gbg.samples.onboarding.go.live.dto.GoStartResponse;
import com.gbg.samples.onboarding.go.live.dto.GoStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Real GBG Go v2 integration. Every path here corresponds to a documented
 * endpoint (see {@code /docs/go-v2/api-reference/endpoint/*} in the GBG
 * documentation): token mint, {@code POST journey/start}, {@code POST
 * journey/interaction/fetch}, {@code POST journey/interaction/submit},
 * {@code POST journey/state/fetch}. Domain-element-to-screen-copy mapping is
 * intentionally generic — see {@link DefaultInteractionMapper}.
 *
 * Structurally complete and exercised against the documented shapes, but not
 * run against a live tenant in building this sample — there is no published
 * journey or credential set available here to test against. Treat first use
 * against a real environment as an integration test, not an assumption.
 */
@Component
@ConditionalOnProperty(prefix = "go", name = "mode", havingValue = "live")
public class GoApiClient implements GoClient {

    private static final Logger log = LoggerFactory.getLogger(GoApiClient.class);

    private final RestClient client;
    private final GoTokenService tokenService;
    private final DefaultInteractionMapper mapper;

    public GoApiClient(GoProperties properties, GoTokenService tokenService,
                        DefaultInteractionMapper mapper, RestClient.Builder builder) {
        this.tokenService = tokenService;
        this.mapper = mapper;
        this.client = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public GoStartResult startJourney(String resourceId, Map<String, Object> prefill, String scenarioHint) {
        GoStartResponse started = call(() -> client.post()
                .uri("journey/start")
                .header("Authorization", "Bearer " + tokenService.accessToken())
                .body(GoStartRequest.of(resourceId, prefill))
                .retrieve()
                .body(GoStartResponse.class));
        if (started == null || started.instanceId() == null) {
            throw OnboardingException.upstreamUnavailable("Could not start your verification. Try again shortly.");
        }
        SubmitInteractionResponse first = fetchInteraction(started.instanceId());
        return new GoStartResult(started.instanceId(), first.status(), first.interaction());
    }

    @Override
    public SubmitInteractionResponse fetchInteraction(String instanceId) {
        GoInteractionFetchResponse response = call(() -> client.post()
                .uri("journey/interaction/fetch")
                .header("Authorization", "Bearer " + tokenService.accessToken())
                .body(new GoInstanceRequest(instanceId))
                .retrieve()
                .body(GoInteractionFetchResponse.class));
        if (response == null) {
            throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
        }
        Interaction interaction = mapper.toInteraction(response);
        return new SubmitInteractionResponse(statusFrom(interaction), interaction);
    }

    @Override
    public SubmitInteractionResponse submitInteraction(String instanceId, String interactionId, Map<String, Object> data) {
        call(() -> client.post()
                .uri("journey/interaction/submit")
                .header("Authorization", "Bearer " + tokenService.accessToken())
                .body(GoInteractionSubmitRequest.of(instanceId, interactionId, data))
                .retrieve()
                .toBodilessEntity());
        // The submit response only acknowledges receipt; the next screen comes
        // from re-fetching the interaction, same as the mock's own response shape.
        return fetchInteraction(instanceId);
    }

    @Override
    public StateResponse fetchState(String instanceId) {
        GoStateResponse response = fetchGoState(instanceId);
        JourneyStatus status = "Completed".equalsIgnoreCase(response.status())
                ? JourneyStatus.COMPLETED
                : JourneyStatus.IN_PROGRESS;
        RecordResponse asRecord = mapper.toRecord(response);
        return new StateResponse(status, asRecord.decision(), asRecord.moduleRuns());
    }

    @Override
    public RecordResponse fetchRecord(String instanceId) {
        return mapper.toRecord(fetchGoState(instanceId));
    }

    private GoStateResponse fetchGoState(String instanceId) {
        GoStateResponse response = call(() -> client.post()
                .uri("journey/state/fetch")
                .header("Authorization", "Bearer " + tokenService.accessToken())
                .body(new GoInstanceRequest(instanceId))
                .retrieve()
                .body(GoStateResponse.class));
        if (response == null) {
            throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
        }
        return response;
    }

    private static JourneyStatus statusFrom(Interaction interaction) {
        return switch (interaction.kind()) {
            case PROCESSING -> JourneyStatus.IN_PROGRESS;
            case RESULT -> interaction.summary() != null && !interaction.summary().isEmpty()
                    ? JourneyStatus.COMPLETED
                    : JourneyStatus.PENDING_INPUT;
            default -> JourneyStatus.PENDING_INPUT;
        };
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            HttpStatusCode statusCode = e.getStatusCode();
            String goDetail = describeGoError(e);
            log.warn("GBG Go call failed: {} {}", statusCode, goDetail);
            if (statusCode.value() == 404 || statusCode.value() == 410) {
                throw OnboardingException.sessionExpired("Your session has ended. Start again to continue.");
            }
            if (statusCode.value() == 400 || statusCode.value() == 422) {
                throw OnboardingException.validationFailed("Some of the details you entered could not be verified.", null);
            }
            if (statusCode.value() == 429) {
                throw new OnboardingException(com.gbg.samples.onboarding.api.dto.ErrorCode.RATE_LIMITED,
                        "Too many attempts. Wait a moment and try again.");
            }
            throw OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
        } catch (OnboardingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling GBG Go", e);
            throw OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
        }
    }

    private String describeGoError(RestClientResponseException e) {
        try {
            GoErrorEnvelope envelope = e.getResponseBodyAs(GoErrorEnvelope.class);
            if (envelope != null && envelope.errors() != null) {
                List<String> problems = envelope.errors().stream()
                        .map(GoErrorEnvelope.GoErrorItem::problem)
                        .toList();
                return String.join("; ", problems);
            }
        } catch (Exception ignored) {
            // Body wasn't the expected shape — fall through to the raw text.
        }
        return e.getResponseBodyAsString();
    }
}
