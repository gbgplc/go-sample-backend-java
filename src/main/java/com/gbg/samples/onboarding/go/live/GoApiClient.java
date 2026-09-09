package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.config.AppConfigProperties;
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

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final AppConfigProperties appConfig;

    /**
     * Last outstanding-elements list seen per Go instance, so a capture submit
     * can tell document from selfie without an extra fetch. Bounded and
     * access-ordered (evicts the least-recently-used entry once full) rather
     * than a plain unbounded map — nothing here ever removes an instance on
     * session expiry or journey completion, so an unbounded cache would grow
     * for the life of the process against a long-running live-mode deployment.
     */
    private static final int MAX_CACHED_INSTANCES = 10_000;

    private final Map<String, List<String>> lastOutstandingByInstance = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_CACHED_INSTANCES;
                }
            });

    public GoApiClient(GoProperties properties, GoTokenService tokenService,
                        DefaultInteractionMapper mapper, AppConfigProperties appConfig, RestClient.Builder builder) {
        this.tokenService = tokenService;
        this.mapper = mapper;
        this.appConfig = appConfig;
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
        if (response.outstanding() != null) {
            lastOutstandingByInstance.put(instanceId, response.outstanding());
        }
        Interaction interaction = mapper.toInteraction(response);
        return new SubmitInteractionResponse(statusFrom(interaction), interaction);
    }

    @Override
    public SubmitInteractionResponse submitInteraction(String instanceId, String interactionId, Map<String, Object> data) {
        Map<String, Object> payload = resolveAttachment(instanceId, data);
        call(() -> client.post()
                .uri("journey/interaction/submit")
                .header("Authorization", "Bearer " + tokenService.accessToken())
                .body(GoInteractionSubmitRequest.of(instanceId, interactionId, payload, appConfig.consentUrl()))
                .retrieve()
                .toBodilessEntity());
        // The submit response only acknowledges receipt; the next screen comes
        // from re-fetching the interaction, same as the mock's own response shape.
        return fetchInteraction(instanceId);
    }

    @Override
    public StateResponse fetchState(String instanceId) {
        GoStateResponse response = fetchGoState(instanceId);
        // A Failed journey is terminal. Reporting IN_PROGRESS would leave the
        // front end's processing screen polling an instance that will never
        // advance, so it reports COMPLETED — the decision it carries is `fail`.
        JourneyStatus status = "Completed".equalsIgnoreCase(response.status())
                || DefaultInteractionMapper.isFailed(response.status())
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

    /**
     * A result screen is terminal because it carries a decision, not because
     * the mapper happened to attach a summary. The mock client keys off a
     * non-empty summary instead, but it authors both halves of its own
     * fixtures; here the summary is composed from whatever Go returned and is
     * legitimately empty on a failed journey, which must still read as
     * terminal rather than parking the customer on a dead screen.
     */
    /**
     * Rewrites the front end's capture submission into a field the submit
     * mapping recognises.
     *
     * The capture screen posts {@code {attachmentRef: <base64>}} for both a
     * document and a selfie — the two screens are the same component, so the
     * payload is identical and the key alone can't say which is which. Go
     * needs them in different places ({@code subject.documents[].side1Image}
     * versus {@code subject.biometrics[].selfieImage}), so the distinction has
     * to come from the journey's own state: whichever image element is still
     * outstanding is the one being answered.
     *
     * The front end always fetches the current interaction to render the
     * screen it's now submitting, so {@code fetchInteraction} has already
     * populated {@link #lastOutstandingByInstance} for this instance — reusing
     * that avoids a third Go call (fetch-to-disambiguate, submit, fetch-for-
     * next-screen) on every single capture. A live fetch is only the
     * fallback, for the unlikely case nothing has been cached yet (e.g. right
     * after a restart).
     */
    private Map<String, Object> resolveAttachment(String instanceId, Map<String, Object> data) {
        Object ref = data == null ? null : data.get("attachmentRef");
        if (ref == null) return data;

        List<String> outstanding = lastOutstandingByInstance.getOrDefault(instanceId, null);
        if (outstanding == null) {
            outstanding = fetchOutstanding(instanceId);
        }
        boolean document = outstanding.stream().anyMatch(o -> o.startsWith("PrimaryDocument/"));

        Map<String, Object> rewritten = new java.util.LinkedHashMap<>(data);
        rewritten.remove("attachmentRef");
        rewritten.put(document ? "documentImage" : "selfieImage", ref);
        return rewritten;
    }

    private List<String> fetchOutstanding(String instanceId) {
        GoInteractionFetchResponse response = call(() -> client.post()
                .uri("journey/interaction/fetch")
                .header("Authorization", "Bearer " + tokenService.accessToken())
                .body(new GoInstanceRequest(instanceId))
                .retrieve()
                .body(GoInteractionFetchResponse.class));
        return response == null || response.outstanding() == null ? List.of() : response.outstanding();
    }

    private static JourneyStatus statusFrom(Interaction interaction) {
        return switch (interaction.kind()) {
            case PROCESSING -> JourneyStatus.IN_PROGRESS;
            case RESULT -> interaction.decision() != null
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
                throw OnboardingException.rateLimited("Too many attempts. Wait a moment and try again.");
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
