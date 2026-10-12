package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.Go;
import com.gbg.gocore.models.InteractionFetchResponse;
import com.gbg.gocore.models.InteractionFetchResponseJourney;
import com.gbg.gocore.models.SlimInteractionFetchResponse;
import com.gbg.gocore.models.InteractionSubmitResponse;
import com.gbg.gocore.models.InteractionSubmitResponseStatus;
import com.gbg.gocore.models.JourneyStartResponse;
import com.gbg.gocore.models.StateFetchResponse;
import com.gbg.gocore.models.errors.GoException;
import com.gbg.gocore.utils.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.gbg.gocore.models.operations.FetchInteractionRequestBody;
import com.gbg.gocore.models.operations.FetchInteractionResponse;
import com.gbg.gocore.models.operations.FetchInteractionResponseBody;
import com.gbg.gocore.models.operations.FetchInteractionSecurity;
import com.gbg.gocore.models.operations.GetJourneyStateRequestBody;
import com.gbg.gocore.models.operations.GetJourneyStateResponse;
import com.gbg.gocore.models.operations.GetJourneyStateResponseBody;
import com.gbg.gocore.models.operations.StartJourneyRequest;
import com.gbg.gocore.models.operations.StartJourneyResponse;
import com.gbg.gocore.models.operations.SubmitInteractionRequest;
import com.gbg.gocore.models.operations.SubmitInteractionResponse;
import com.gbg.gocore.models.operations.SubmitInteractionSecurity;
import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.GoSdkProperties;
import com.gbg.samples.onboarding.go.GoClient;
import com.gbg.samples.onboarding.go.GoStartResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Real GBG Go v2 integration built on {@code com.gbg:go-core-sdk} — the
 * go-core-sdk sibling of api-implementation's {@code GoApiClient}. Ports the
 * same four bounded caches, the same {@code resolveAttachment()} capture-
 * routing logic, the same only-mark-completed-on-success submit behaviour,
 * and the same three-way terminal check in {@code fetchState}, against the
 * SDK's operations instead of raw HTTP calls (see method-by-method javadoc
 * below for what changed and why).
 *
 * Written against the shapes in the SDK's generated source (see
 * {@code docs/sdk-jar-inspection/FINDINGS.md} and this module's README,
 * "Upgrading to 0.2.0-alpha01"); read the README's "Known gaps" first.
 */
@Component
@ConditionalOnProperty(prefix = "go", name = "mode", havingValue = "live")
public class GoSdkClient implements GoClient {

    private static final Logger log = LoggerFactory.getLogger(GoSdkClient.class);
    private static final int MAX_CACHED_INSTANCES = 10_000;

    private final GoSdkProperties properties;
    private final GoSdkAuthService authService;
    private final SdkInteractionMapper mapper;
    private final AppConfigProperties appConfig;

    /** Same purpose and bound as GoApiClient's — see its javadoc for the full rationale. */
    private final Map<String, List<String>> lastOutstandingByInstance = boundedMap();
    private final Map<String, List<String>> lastInstructionsByInstance = boundedMap();
    private final Map<String, List<String>> lastCollectableByInstance = boundedMap();
    private final Map<String, Set<String>> completedStagesByInstance = boundedMap();

    private static <V> Map<String, V> boundedMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_CACHED_INSTANCES;
            }
        });
    }

    /**
     * {@code Go} is immutable once built (no setter to swap {@code customerAccess}
     * — confirmed against the real SDK source, see FINDINGS.md Q7), so a fresh
     * instance is built whenever the cached token rotates, guarded the same
     * way {@code GoTokenService}/{@code GoSdkAuthService} guard their own
     * token cache.
     */
    private volatile Go goClient;
    private volatile String goClientToken;

    /** Shared across every {@code Go} instance this client builds; stateless. */
    private final TimeoutHttpClient httpClient = new TimeoutHttpClient();

    public GoSdkClient(GoSdkProperties properties, GoSdkAuthService authService,
                        SdkInteractionMapper mapper, AppConfigProperties appConfig) {
        this.properties = properties;
        this.authService = authService;
        this.mapper = mapper;
        this.appConfig = appConfig;
    }

    private synchronized Go currentGoClient(String token) {
        if (goClient == null || !token.equals(goClientToken)) {
            Go.Builder builder = Go.builder().customerAccess(token).client(httpClient);
            // usesDocumentedRegion(): region is one of eu/us/au and no explicit
            // base-url override is configured, so serverIndex(0/1/2) — confirmed
            // to map exactly onto the documented hosts, FINDINGS.md Q7 — is
            // preferred over serverURL(), matching how GoSdkProperties.baseUrl()
            // would otherwise have composed the same host by string concatenation.
            if (properties.usesDocumentedRegion()) {
                builder.serverIndex(properties.serverIndex());
            } else {
                builder.serverURL(properties.sdkServerUrl());
            }
            goClient = builder.build();
            goClientToken = token;
        }
        return goClient;
    }

    @Override
    public GoStartResult startJourney(String resourceId, Map<String, Object> prefill, String scenarioHint) {
        StartJourneyRequest request = mapper.toStartRequest(resourceId, prefill);
        StartJourneyResponse response = call(() -> authorized(token -> currentGoClient(token).journeys().start(request)));
        String instanceId = response.journeyStartResponse()
                .map(JourneyStartResponse::instanceId)
                .orElse(null);
        if (instanceId == null) {
            throw OnboardingException.upstreamUnavailable("Could not start your verification. Try again shortly.");
        }
        SubmitInteractionResponseAlias first = fetchInteractionInternal(instanceId);
        return new GoStartResult(instanceId, first.status(), first.interaction());
    }

    @Override
    public com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse fetchInteraction(String instanceId) {
        SubmitInteractionResponseAlias result = fetchInteractionInternal(instanceId);
        return new com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse(result.status(), result.interaction());
    }

    /**
     * Small internal tuple so {@link #startJourney} and {@link #fetchInteraction}
     * can share one implementation without importing the DTO record twice —
     * {@code com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse} is
     * the actual public return shape, constructed at each call site.
     */
    private record SubmitInteractionResponseAlias(JourneyStatus status, Interaction interaction) {
    }

    private SubmitInteractionResponseAlias fetchInteractionInternal(String instanceId) {
        FetchInteractionResponse response = call(() -> authorized(token -> currentGoClient(token).interactions()
                .fetch(interactionSecurity(token), null, FetchInteractionRequestBody.builder().instanceId(instanceId).build())));
        FetchInteractionResponseBody oneOf = response.oneOf().orElse(null);
        if (oneOf == null) {
            throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
        }

        // Either variant can arrive for the same full-view call: the SDK picks
        // the union member whose declared fields best match the JSON, and Go's
        // fetch response has no `context`, which only the full variant
        // declares — so a typical response deserializes as the slim one.
        Optional<InteractionFetchResponse> full = oneOf.interactionFetchResponse()
                .or(() -> oneOf.slimInteractionFetchResponse().map(GoSdkClient::fromSlim));
        if (full.isPresent()) {
            InteractionFetchResponse body = full.get();
            // Always overwritten, empty when Go omits the field: keeping the
            // last non-null value let a stale Side2Required outlive the
            // document screen and route the selfie into side2Image.
            List<String> outstanding = SdkInteractionMapper.stringList(body.outstanding().orElse(null));
            lastOutstandingByInstance.put(instanceId, outstanding);
            lastInstructionsByInstance.put(instanceId, SdkInteractionMapper.stringList(body.instructions().orElse(null)));
            List<String> collectable = SdkInteractionMapper.collectableRefs(body);
            lastCollectableByInstance.put(instanceId, collectable.isEmpty() ? outstanding : collectable);
            Interaction interaction = mapper.toInteraction(body, completedStages(instanceId));
            return new SubmitInteractionResponseAlias(statusFrom(interaction), interaction);
        }

        // asJson(): a shape neither variant matched — including an in-band
        // error, which 0.1.0 modelled and 0.2.0 no longer does.
        JsonNode json = oneOf.asJson().orElse(null);
        OnboardingException inBand = inBandError(json);
        if (inBand != null) {
            log.warn("GBG Go fetch-interaction returned an in-band error for instance {}: {}", instanceId, json);
            throw inBand;
        }
        log.warn("Fetch-interaction response matched no expected shape for instance {}: {}", instanceId, json);
        throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
    }

    /**
     * A 200 body of the form {@code {"status": "error", "code": 410, ...}},
     * classified by its code like an HTTP error; null for anything else.
     * Package-private for {@code GoSdkClientInBandErrorTest}.
     */
    static OnboardingException inBandError(JsonNode json) {
        if (json == null || !"error".equalsIgnoreCase(json.path("status").asText())) return null;
        JsonNode code = json.path("code");
        return code.canConvertToInt() || code.isTextual() && code.asText().matches("\\d+")
                ? classifyCode(code.asInt())
                : OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
    }

    /** The slim variant's fields that the mapper reads, as the full variant. */
    static InteractionFetchResponse fromSlim(SlimInteractionFetchResponse slim) {
        return InteractionFetchResponse.builder()
                .instanceId(slim.instanceId())
                .interactionId(slim.interactionId().orElse(null))
                .journey(new InteractionFetchResponseJourney(slim.journey().status().value()))
                .interaction(slim.interaction().orElse(null))
                .outstanding(slim.outstanding().orElse(null))
                .instructions(slim.instructions().orElse(null))
                .processing(slim.processing().orElse(null))
                .build();
    }

    /**
     * The interaction token. 0.2.0's security classes carry two bearer
     * schemes ({@code customerAccess}, {@code interactionAccess}) where 0.1.0
     * had one; this sets the same one 0.1.0's single-argument constructor did.
     */
    private static FetchInteractionSecurity interactionSecurity(String token) {
        return FetchInteractionSecurity.builder().interactionAccess(token).build();
    }

    /** Stage names already submitted on this instance; never null. */
    private Set<String> completedStages(String instanceId) {
        Set<String> done = completedStagesByInstance.get(instanceId);
        return done == null ? Set.of() : Set.copyOf(done);
    }

    @Override
    public com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse submitInteraction(
            String instanceId, String interactionId, Map<String, Object> data) {
        Map<String, Object> payload = resolveAttachment(instanceId, data);
        String submittedStage = mapper.stageFor(payload.keySet(), completedStages(instanceId));
        log.debug("Submitting to Go: instance={} stage={} keys={}", instanceId, submittedStage, payload.keySet());

        SubmitInteractionRequest request = mapper.toSubmitRequest(instanceId, interactionId, payload, appConfig.consentUrl());
        SubmitInteractionResponse response = call(() -> authorized(token -> currentGoClient(token).interactions()
                .submit(request, SubmitInteractionSecurity.builder().interactionAccess(token).build())),
                mapper.fieldsByGoPath(payload));
        throwIfSubmitError(response);

        if (submittedStage != null) {
            completedStagesByInstance
                    .computeIfAbsent(instanceId, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                    .add(submittedStage);
        }
        return fetchInteraction(instanceId);
    }

    @Override
    public StateResponse fetchState(String instanceId) {
        StateFetchResponse state = fetchGoState(instanceId);
        RecordResponse asRecord = mapper.toRecord(state);
        String goStatus = state.status();

        // Same three-way terminal check as GoApiClient.fetchState — see its
        // javadoc for the full rationale (Completed / Failed-or-Error / a
        // decision reached with every module settled, because the Northbank
        // journey settles on `refer` without ever reaching status Completed).
        boolean carriesRealResult = mapper.carriesRealResult(state);
        boolean everyModuleSettled = !asRecord.moduleRuns().isEmpty()
                && asRecord.moduleRuns().stream().noneMatch(run -> ModuleState.RUNNING.equals(run.state()));
        boolean decided = carriesRealResult && everyModuleSettled;

        JourneyStatus status = "Completed".equalsIgnoreCase(goStatus)
                || SdkInteractionMapper.isFailed(goStatus)
                || decided
                ? JourneyStatus.COMPLETED
                : JourneyStatus.IN_PROGRESS;
        return new StateResponse(status, asRecord.decision(), asRecord.moduleRuns());
    }

    @Override
    public RecordResponse fetchRecord(String instanceId) {
        return mapper.toRecord(fetchGoState(instanceId));
    }

    /**
     * The full (default) state view. 0.1.0 dropped this response's
     * {@code result} and per-step detail, which needed a raw-body capture to
     * recover; 0.2.0's {@code StateFetchResponse} keeps both.
     */
    private StateFetchResponse fetchGoState(String instanceId) {
        GetJourneyStateResponse response = call(() -> authorized(token -> currentGoClient(token).journeys()
                .getState(null, GetJourneyStateRequestBody.builder().instanceId(instanceId).build())));
        GetJourneyStateResponseBody body = response.oneOf().orElse(null);
        Optional<StateFetchResponse> full = body == null ? Optional.empty() : body.stateFetchResponse();
        if (full.isPresent()) {
            return full.get();
        }
        // The slim variant has no per-step status or timing, so it cannot
        // stand in for the full one. The SDK picks the union member by best
        // field match, so a full-view body could in principle land here.
        if (body != null && body.slimStateFetchResponse().isPresent()) {
            log.warn("Journey-state response for instance {} deserialized as the SDK's slim variant, "
                    + "which lacks the step detail the record needs: {}", instanceId, body.slimStateFetchResponse().get());
        } else {
            JsonNode json = body == null ? null : body.asJson().orElse(null);
            OnboardingException inBand = inBandError(json);
            if (inBand != null) {
                log.warn("GBG Go journey-state returned an in-band error for instance {}: {}", instanceId, json);
                throw inBand;
            }
            log.warn("Journey-state response matched no expected shape for instance {}: {}",
                    instanceId, json == null ? "no body" : json);
        }
        throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
    }

    /**
     * Rewrites the front end's capture submission into a field the submit
     * mapping recognises — ported verbatim from {@code GoApiClient}'s method
     * of the same name (see its javadoc for the full document/selfie/
     * document-back routing rationale); only the SDK-vs-raw-HTTP fetch call
     * inside the cache-miss fallback differs.
     */
    private Map<String, Object> resolveAttachment(String instanceId, Map<String, Object> data) {
        Object ref = data == null ? null : data.get("attachmentRef");
        if (ref == null) return data;

        if (!lastOutstandingByInstance.containsKey(instanceId)) {
            // Cache miss: fetchInteractionInternal() populates all three
            // caches this method reads (outstanding, instructions,
            // collectable) from one fetch — the same call every other read
            // of this instance already goes through. Previously this used a
            // narrower one-off fetch (fetchOutstanding, since removed) that
            // only ever populated `outstanding`, silently leaving
            // instructions/collectable empty on a cold cache for the very
            // checks right below that read them.
            fetchInteractionInternal(instanceId);
        }
        List<String> outstanding = lastOutstandingByInstance.getOrDefault(instanceId, List.of());

        if (lastInstructionsByInstance.getOrDefault(instanceId, List.of()).stream()
                        .anyMatch("Side2Required"::equalsIgnoreCase)
                || outstanding.contains("PrimaryDocument/side2Image")) {
            log.debug("Capture routed as DOCUMENT BACK: instance={} outstanding={}", instanceId, outstanding);
            Map<String, Object> back = new LinkedHashMap<>(data);
            back.remove("attachmentRef");
            back.put("documentBack", ref);
            return back;
        }

        List<String> collectable = lastCollectableByInstance.getOrDefault(instanceId, outstanding);
        Boolean capturingDocument = mapper.currentCaptureIsDocument(collectable, completedStages(instanceId));
        boolean document = capturingDocument != null
                ? capturingDocument
                : collectable.stream().anyMatch(o -> o.startsWith("PrimaryDocument/"));

        log.debug("Capture routed as {}: instance={} completed={} outstanding={}",
                document ? "DOCUMENT" : "SELFIE", instanceId, completedStages(instanceId), outstanding);

        Map<String, Object> rewritten = new LinkedHashMap<>(data);
        rewritten.remove("attachmentRef");
        rewritten.put(document ? "documentImage" : "selfieImage", ref);
        return rewritten;
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

    /**
     * Runs {@code request} with the current access token, retrying once with a
     * freshly minted one if Go answers 401/403. Without the retry, a token Go
     * has stopped accepting (revoked, rotated early, clock skew) stays cached
     * and every call fails until its expiry — up to an hour under
     * client_credentials. Anything else — including a second rejection —
     * propagates for {@link #call} to classify.
     */
    <T> T authorized(Function<String, T> request) {
        String token = authService.accessToken();
        try {
            return request.apply(token);
        } catch (GoException e) {
            if (e.code() != 401 && e.code() != 403) {
                throw e;
            }
            log.warn("GBG Go rejected the access token ({}); minting a new one and retrying once", e.code());
            authService.invalidate(token);
        }
        return request.apply(authService.accessToken());
    }

    <T> T call(Supplier<T> request) {
        return call(request, Map.of());
    }

    /**
     * Ported from {@code GoApiClient.call()}. Catches {@link GoException}, the
     * base of both exceptions 0.2.0 throws on 4XX/5XX: {@code ErrorResponse}
     * for the documented error codes and {@code APIException} for the rest.
     *
     * @param fieldsByGoPath for a submit, where each submitted field landed in
     *                       the request (see {@link SdkInteractionMapper#fieldsByGoPath}),
     *                       so a 400/422 can name the fields Go rejected.
     */
    <T> T call(Supplier<T> request, Map<String, String> fieldsByGoPath) {
        try {
            return request.get();
        } catch (GoException e) {
            String body = e.bodyAsString().orElse("");
            log.warn("GBG Go call failed: {} {}", e.code(), body);
            if ((e.code() == 400 || e.code() == 422) && !fieldsByGoPath.isEmpty()) {
                Map<String, String> fields = rejectedFields(goProblems(body), fieldsByGoPath);
                if (!fields.isEmpty()) {
                    throw OnboardingException.validationFailed("Some of the details you entered could not be verified.", fields);
                }
            }
            throw classifyCode(e.code());
        } catch (OnboardingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling GBG Go", e);
            throw OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
        }
    }

    /**
     * A 200 submit is only a success if its body says so. 0.1.0 modelled an
     * in-band error variant with a code; 0.2.0's {@code InteractionSubmitResponse}
     * has only {@code status} (an open enum whose one known value is
     * {@code success}), so anything else is treated as an upstream failure —
     * never marked completed and reported as a success.
     *
     * <p>Package-private so {@code GoSdkClientSubmitInteractionErrorTest} can
     * exercise it directly against a hand-built {@link SubmitInteractionResponse}.
     */
    void throwIfSubmitError(SubmitInteractionResponse response) {
        String status = response.interactionSubmitResponse()
                .map(InteractionSubmitResponse::status)
                .map(InteractionSubmitResponseStatus::value)
                .orElse(null);
        if (!InteractionSubmitResponseStatus.SUCCESS.value().equals(status)) {
            log.warn("GBG Go submit-interaction answered 200 without success status: {}", status);
            throw OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
        }
    }

    /** Every {@code errors[].problem} in a Go error body, joined with "; " — empty if it isn't that shape. */
    static String goProblems(String body) {
        try {
            List<String> problems = new java.util.ArrayList<>();
            JSON.getMapper().readTree(body).path("errors").forEach(error -> {
                String problem = error.path("problem").asText("");
                if (!problem.isBlank()) problems.add(problem);
            });
            return String.join("; ", problems);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Submitted field → Go's message, for every {@code path: message} segment
     * in {@code problems} whose path is one this submit sent (live format,
     * 2026-09-29: {@code context.subject.identity.emails.0.email: Invalid email address}).
     */
    static Map<String, String> rejectedFields(String problems, Map<String, String> fieldsByGoPath) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String segment : problems.split(";\\s*")) {
            int colon = segment.indexOf(": ");
            if (colon <= 0) continue;
            String field = fieldsByGoPath.get(segment.substring(0, colon).trim());
            if (field != null) {
                fields.putIfAbsent(field, segment.substring(colon + 2).trim());
            }
        }
        return fields;
    }

    private static OnboardingException classifyCode(int code) {
        if (code == 404 || code == 410) {
            return OnboardingException.sessionExpired("Your session has ended. Start again to continue.");
        }
        if (code == 400 || code == 422) {
            return OnboardingException.validationFailed("Some of the details you entered could not be verified.", null);
        }
        if (code == 429) {
            return OnboardingException.rateLimited("Too many attempts. Wait a moment and try again.");
        }
        return OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
    }
}
