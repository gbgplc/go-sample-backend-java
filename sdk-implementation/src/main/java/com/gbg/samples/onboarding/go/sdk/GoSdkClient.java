package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.Go;
import com.gbg.gocore.models.errors.APIException;
import com.gbg.gocore.utils.JSON;
import com.gbg.gocore.models.operations.FetchInteractionError;
import com.gbg.gocore.models.operations.FetchInteractionRequest;
import com.gbg.gocore.models.operations.FetchInteractionResponse;
import com.gbg.gocore.models.operations.FetchInteractionResponseBody;
import com.gbg.gocore.models.operations.FetchInteractionSecurity;
import com.gbg.gocore.models.operations.GetJourneyStateRequest;
import com.gbg.gocore.models.operations.GetJourneyStateResponse;
import com.gbg.gocore.models.operations.GetJourneyStateResponseBody;
import com.gbg.gocore.models.operations.ResponseBody1;
import com.gbg.gocore.models.operations.ResponseBody2;
import com.gbg.gocore.models.operations.StartJourneyRequest;
import com.gbg.gocore.models.operations.StartJourneyResponse;
import com.gbg.gocore.models.operations.SubmitInteractionError;
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
 * Structurally complete and exercised against the shapes confirmed by
 * reading the SDK's real generated source (see
 * {@code docs/sdk-jar-inspection/FINDINGS.md}), but — like
 * api-implementation's own client before it — not run against a live tenant
 * while building this sample; there is no credential set available in this
 * environment to test against. Treat first use against a real environment as
 * an integration test, not an assumption, and read this module's README
 * "Known gaps" section first.
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

    /**
     * Shared across every {@code Go} instance this client builds (see
     * {@link #currentGoClient}) — it holds no per-token state, only the
     * thread-local buffer {@link #fetchGoState} reads right after each
     * {@code journey/state/fetch} call. See {@link RawStateBodyCapturingHttpClient}'s
     * javadoc and {@link SdkInteractionMapper}'s for why this exists: the
     * SDK's typed response for that one operation silently drops the
     * journey decision and per-module results.
     */
    private final RawStateBodyCapturingHttpClient rawStateHttpClient = new RawStateBodyCapturingHttpClient();

    public GoSdkClient(GoSdkProperties properties, GoSdkAuthService authService,
                        SdkInteractionMapper mapper, AppConfigProperties appConfig) {
        this.properties = properties;
        this.authService = authService;
        this.mapper = mapper;
        this.appConfig = appConfig;
    }

    private synchronized Go currentGoClient(String token) {
        if (goClient == null || !token.equals(goClientToken)) {
            Go.Builder builder = Go.builder().customerAccess(token).client(rawStateHttpClient);
            // usesDocumentedRegion(): region is one of eu/us/au and no explicit
            // base-url override is configured, so serverIndex(0/1/2) — confirmed
            // to map exactly onto the documented hosts, FINDINGS.md Q7 — is
            // preferred over serverURL(), matching how GoSdkProperties.baseUrl()
            // would otherwise have composed the same host by string concatenation.
            if (properties.usesDocumentedRegion()) {
                builder.serverIndex(properties.serverIndex());
            } else {
                builder.serverURL(properties.baseUrl());
            }
            goClient = builder.build();
            goClientToken = token;
        }
        return goClient;
    }

    @Override
    public GoStartResult startJourney(String resourceId, Map<String, Object> prefill, String scenarioHint) {
        String token = authService.accessToken();
        Go go = currentGoClient(token);
        StartJourneyRequest request = mapper.toStartRequest(resourceId, prefill);
        StartJourneyResponse response = call(() -> go.journeys().start(request));
        String instanceId = response.twoHundredApplicationJsonObject()
                .map(b -> b.instanceId())
                .or(() -> response.twoHundredAndOneApplicationJsonObject().map(b -> b.instanceId()))
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
        String token = authService.accessToken();
        Go go = currentGoClient(token);
        FetchInteractionSecurity security = new FetchInteractionSecurity(token);
        FetchInteractionResponse response = call(() -> go.interactions()
                .fetch(new FetchInteractionRequest(instanceId), security));
        FetchInteractionResponseBody oneOf = response.oneOf().orElse(null);
        if (oneOf == null) {
            throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
        }

        Optional<FetchInteractionError> error = oneOf.fetchInteractionError();
        if (error.isPresent()) {
            throw classifyInBandError(error.get());
        }

        Optional<ResponseBody1> full = oneOf.responseBody1();
        if (full.isPresent()) {
            ResponseBody1 body = full.get();
            List<String> outstanding = body.outstanding().orElse(null);
            if (outstanding != null) {
                lastOutstandingByInstance.put(instanceId, outstanding);
            }
            List<String> instructions = body.instructions().orElse(null);
            if (instructions != null) {
                lastInstructionsByInstance.put(instanceId, instructions);
            }
            List<String> collectable = SdkInteractionMapper.collectableRefs(body);
            lastCollectableByInstance.put(instanceId, collectable.isEmpty()
                    ? (outstanding == null ? List.of() : outstanding)
                    : collectable);
            Interaction interaction = mapper.toInteraction(body, completedStages(instanceId));
            return new SubmitInteractionResponseAlias(statusFrom(interaction), interaction);
        }

        Optional<ResponseBody2> stub = oneOf.responseBody2();
        if (stub.isPresent()) {
            ResponseBody2 body = stub.get();
            // No interactionId field on this shape — the raw client's own
            // fallback (interactionId, else instanceId) applies identically.
            Interaction interaction = mapper.processingOrTerminalWithoutInteraction(
                    body.instanceId(), body.journey().status().value(), body.processing().orElse(false));
            return new SubmitInteractionResponseAlias(statusFrom(interaction), interaction);
        }

        // asJson() — a shape none of the three known union members matched.
        log.warn("Fetch-interaction response matched no known shape for instance {}", instanceId);
        throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
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

        String token = authService.accessToken();
        Go go = currentGoClient(token);
        SubmitInteractionRequest request = mapper.toSubmitRequest(instanceId, interactionId, payload, appConfig.consentUrl());
        SubmitInteractionSecurity security = new SubmitInteractionSecurity(token);
        SubmitInteractionResponse response = call(() -> go.interactions().submit(request, security));
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
        GoState state = fetchGoState(instanceId);
        RecordResponse asRecord = mapper.toRecord(state.body(), state.rawStateBody());
        String goStatus = state.body().status().value();

        // Same three-way terminal check as GoApiClient.fetchState — see its
        // javadoc for the full rationale (Completed / Failed-or-Error / a
        // decision reached with every module settled, because the Northbank
        // journey settles on `refer` without ever reaching status Completed).
        boolean carriesRealResult = mapper.carriesRealResult(state.body(), state.rawStateBody());
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
        GoState state = fetchGoState(instanceId);
        return mapper.toRecord(state.body(), state.rawStateBody());
    }

    /**
     * @param rawStateBody the same response's wire bytes, parsed independently
     *                      of the SDK's typed model — see
     *                      {@link RawStateBodyCapturingHttpClient} and
     *                      {@link SdkInteractionMapper}'s javadoc for why.
     *                      Never null; empty if the raw bytes could not be
     *                      captured or parsed, in which case callers fall
     *                      back to {@code body.data()} (itself always empty
     *                      in practice, but cheap to try).
     */
    private record GoState(GetJourneyStateResponseBody body, Map<String, Object> rawStateBody) {
    }

    private GoState fetchGoState(String instanceId) {
        String token = authService.accessToken();
        Go go = currentGoClient(token);
        try {
            GetJourneyStateResponse response = call(() -> go.journeys().getState(new GetJourneyStateRequest(instanceId)));
            GetJourneyStateResponseBody body = response.object().orElse(null);
            if (body == null) {
                throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
            }
            Map<String, Object> rawStateBody = rawStateHttpClient.takeLastStateFetchBody()
                    .map(this::parseRawStateBody)
                    .orElse(Map.of());
            return new GoState(body, rawStateBody);
        } finally {
            // takeLastStateFetchBody() already clears on the success path above;
            // this is the failure path's clear — call() throwing (or body being
            // null) skips straight past it otherwise, leaving the captured bytes
            // pinned to this pooled thread's RawStateBodyCapturingHttpClient
            // ThreadLocal until some later call on the same thread happens to
            // overwrite or read it. A second read here is a safe no-op once the
            // success path has already cleared it.
            rawStateHttpClient.takeLastStateFetchBody();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRawStateBody(byte[] bytes) {
        try {
            return JSON.getMapper().readValue(bytes, Map.class);
        } catch (Exception e) {
            log.warn("Could not parse the raw journey/state/fetch body; falling back to the SDK's own (empty) data map", e);
            return Map.of();
        }
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
     * Ported from {@code GoApiClient.call()} against
     * {@link APIException} instead of {@code RestClientResponseException} —
     * the exception type the spike confirmed every generated operation
     * actually throws on 4XX/5XX (FINDINGS.md Q1; {@code GoException} is its
     * abstract base, {@code APIException} the concrete class).
     */
    <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (APIException e) {
            log.warn("GBG Go call failed: {} {}", e.code(), e.bodyAsString().orElse(""));
            throw classifyCode(e.code());
        } catch (OnboardingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling GBG Go", e);
            throw OnboardingException.upstreamUnavailable("Something went wrong on our end. Try again shortly.");
        }
    }

    /**
     * {@code FetchInteractionResponseBody} can carry an in-band error object
     * on an otherwise-200 response (a {@code oneOf} branch distinct from an
     * {@link APIException}, which the SDK reserves for 4XX/5XX transport
     * errors) — classified the same way.
     */
    private OnboardingException classifyInBandError(FetchInteractionError error) {
        log.warn("GBG Go fetch-interaction returned an in-band error: {} {} {}",
                error.status(), error.code(), error.message());
        return classifyCode((int) error.code());
    }

    /**
     * {@code SubmitInteractionResponseBody} carries the same kind of in-band
     * error on an otherwise-200 submit response — previously discarded
     * entirely, which let a rejected submission (e.g. a stale interactionId)
     * be marked completed and reported to the caller as a success anyway.
     */
    private OnboardingException classifyInBandError(SubmitInteractionError error) {
        log.warn("GBG Go submit-interaction returned an in-band error: {} {} {}",
                error.status(), error.code(), error.message());
        return classifyCode((int) error.code());
    }

    /**
     * Package-private so {@code GoSdkClientSubmitInteractionErrorTest} can
     * exercise it directly against a hand-built {@link SubmitInteractionResponse},
     * the same way {@link #call} is tested without a live Go call.
     */
    void throwIfSubmitError(SubmitInteractionResponse response) {
        if (response.oneOf().orElse(null) instanceof SubmitInteractionError error) {
            throw classifyInBandError(error);
        }
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
