package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ModuleState;
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
import java.util.function.Function;

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

    /**
     * Last instructions seen per Go instance, so a capture submit can tell the
     * back of a document from the front without an extra fetch — the same
     * reason {@link #lastOutstandingByInstance} exists, and populated from the
     * same response. {@code Side2Required} is the signal that matters.
     */
    private final Map<String, List<String>> lastInstructionsByInstance = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_CACHED_INSTANCES;
                }
            });

    /**
     * The same {@code collects}-preferred-over-{@code outstanding} list
     * {@link DefaultInteractionMapper#toInteraction} selects screens against,
     * cached per instance so {@link #resolveAttachment} can classify a
     * capture against the identical data the screen was rendered from.
     *
     * Kept separate from {@link #lastOutstandingByInstance}, which stays raw
     * on purpose: the side-2 check in {@code resolveAttachment} needs Go's
     * real {@code outstanding} (collects lists {@code PrimaryDocument/side2Image}
     * from the first fetch, which would open the back-of-document screen
     * before the front had been captured). Using the raw list here instead
     * was the bug — a document whose parent is optional (Meridian's and
     * Northbank's {@code PrimaryDocument} both are) never appears in it, so
     * {@code currentCaptureIsDocument} always returned null and the fallback
     * heuristic below it failed the same way, submitting the document photo
     * as {@code selfieImage}. Document Classification then never receives an
     * image and the journey cannot progress past that screen.
     */
    private final Map<String, List<String>> lastCollectableByInstance = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_CACHED_INSTANCES;
                }
            });

    /**
     * Stage names already submitted, per Go instance — the journey's progress.
     *
     * Go's {@code outstanding} list is a static declaration of everything the
     * journey collects, not a shrinking to-do list. Verified against the
     * Northbank journey on {@code gbggo4-demo} (2026-09-10): submitting the
     * whole address returns {@code {"status":"success"}} and the very next
     * fetch returns a byte-identical {@code outstanding}.
     *
     * That breaks the rule the screen plan was written against — "first stage
     * whose elements are still outstanding wins" — because the first stage
     * always still claims something. The customer submits the address, the
     * next fetch picks the same stage again, and the journey loops on screen
     * one forever. It went unnoticed while every live market collected through
     * capture and consent screens, which submit one element each and finish;
     * Northbank is the first with a FORM stage.
     *
     * So progress is tracked here instead: a stage is done once submitted, and
     * {@code toInteraction} skips it. Same lifetime and bound as the cache
     * above — in-memory and single-node, which is what {@code SessionStore}
     * already is, and the note there about swapping for Redis applies equally.
     */
    private final Map<String, java.util.Set<String>> completedStagesByInstance = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, java.util.Set<String>> eldest) {
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
        GoStartResponse started = call(token -> client.post()
                .uri("journey/start")
                .header("Authorization", "Bearer " + token)
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
        GoInteractionFetchResponse response = call(token -> client.post()
                .uri("journey/interaction/fetch")
                .header("Authorization", "Bearer " + token)
                .body(new GoInstanceRequest(instanceId))
                .retrieve()
                .body(GoInteractionFetchResponse.class));
        if (response == null) {
            throw OnboardingException.upstreamUnavailable("Could not read your verification status. Try again shortly.");
        }
        if (response.outstanding() != null) {
            lastOutstandingByInstance.put(instanceId, response.outstanding());
        }
        if (response.instructions() != null) {
            lastInstructionsByInstance.put(instanceId, response.instructions());
        }
        // Same precedence as toInteraction below: collects when the
        // interaction carries any, otherwise raw outstanding.
        List<String> collectable = response.collects().stream()
                .map(GoInteractionFetchResponse.Collect::ref)
                .toList();
        lastCollectableByInstance.put(instanceId, collectable.isEmpty()
                ? (response.outstanding() == null ? List.of() : response.outstanding())
                : collectable);
        Interaction interaction = mapper.toInteraction(response, completedStages(instanceId));
        return new SubmitInteractionResponse(statusFrom(interaction), interaction);
    }

    /** Stage names already submitted on this instance; never null. */
    private java.util.Set<String> completedStages(String instanceId) {
        java.util.Set<String> done = completedStagesByInstance.get(instanceId);
        return done == null ? java.util.Set.of() : java.util.Set.copyOf(done);
    }

    @Override
    public SubmitInteractionResponse submitInteraction(String instanceId, String interactionId, Map<String, Object> data) {
        Map<String, Object> payload = resolveAttachment(instanceId, data);
        // Which stage this submit answers, read before the call so a Go
        // rejection leaves progress untouched — a failed submit must not mark
        // its stage done, or a validation error would skip the screen the
        // customer still has to correct.
        String submittedStage = mapper.stageFor(payload.keySet(), completedStages(instanceId));
        // Field names only, never values: enough to see which stage a submit
        // answered when Go rejects it with an opaque 500, without putting a
        // customer's details in the log. This is what identified a front end
        // resubmitting an earlier screen's fields alongside the current one.
        log.debug("Submitting to Go: instance={} stage={} keys={}", instanceId, submittedStage, payload.keySet());
        call(token -> client.post()
                .uri("journey/interaction/submit")
                .header("Authorization", "Bearer " + token)
                .body(GoInteractionSubmitRequest.of(instanceId, interactionId, payload, appConfig.consentUrl()))
                .retrieve()
                .toBodilessEntity(), GoInteractionSubmitRequest.fieldsByGoPath(payload));
        if (submittedStage != null) {
            completedStagesByInstance
                    .computeIfAbsent(instanceId, k -> Collections.synchronizedSet(new java.util.LinkedHashSet<>()))
                    .add(submittedStage);
        }
        // The submit response only acknowledges receipt; the next screen comes
        // from re-fetching the interaction, same as the mock's own response shape.
        return fetchInteraction(instanceId);
    }

    @Override
    public StateResponse fetchState(String instanceId) {
        GoStateResponse response = fetchGoState(instanceId);
        RecordResponse asRecord = mapper.toRecord(response);

        // Terminal on any of three signals, because journeys end differently:
        //
        //  - status "Completed" — the straightforward case;
        //  - Failed or Error — terminal too. Reporting IN_PROGRESS would leave
        //    the processing screen polling an instance that will never advance,
        //    so it reports COMPLETED carrying a `fail` decision;
        //  - a decision reached with every module run finished. The Northbank
        //    journey settles on `refer` without its status ever reaching
        //    Completed (verified on the live tenant, 2026-09-10): the referral
        //    branch leaves the instance open for the out-of-band review the
        //    design describes. Polling for the status alone spins forever on a
        //    journey that has already decided — "Running your checks", with the
        //    decision sitting in the very response being ignored.
        // `asRecord.decision()` cannot answer this on its own: mapDecision
        // defaults a missing result to REFER, so it is non-null from the first
        // poll onwards. The decision is real only when Go sent a result with a
        // classification on it, and every module that ran has stopped running.
        boolean carriesRealResult = response.result() != null
                && response.result().outcomeClassification() != null;
        boolean everyModuleSettled = !asRecord.moduleRuns().isEmpty()
                && asRecord.moduleRuns().stream().noneMatch(run -> ModuleState.RUNNING.equals(run.state()));
        boolean decided = carriesRealResult && everyModuleSettled;

        JourneyStatus status = "Completed".equalsIgnoreCase(response.status())
                || DefaultInteractionMapper.isFailed(response.status())
                || decided
                ? JourneyStatus.COMPLETED
                : JourneyStatus.IN_PROGRESS;
        return new StateResponse(status, asRecord.decision(), asRecord.moduleRuns());
    }

    @Override
    public RecordResponse fetchRecord(String instanceId) {
        return mapper.toRecord(fetchGoState(instanceId));
    }

    private GoStateResponse fetchGoState(String instanceId) {
        GoStateResponse response = call(token -> client.post()
                .uri("journey/state/fetch")
                .header("Authorization", "Bearer " + token)
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

        // Which capture this is, from the stage the customer is actually on.
        //
        // Reading it from `outstanding` alone — "is PrimaryDocument/ still
        // listed?" — is wrong wherever the journey does not advertise the
        // element. Northbank collects its document lazily and never lists it,
        // so every capture classified as a selfie: the document photo was sent
        // as subject.biometrics, Document Classification never received an
        // image, and nothing appeared against the document modules in Go.
        //
        // The stage plan already knows which screen is being submitted, and it
        // is the same source the screen itself was rendered from, so the two
        // cannot disagree. `outstanding` remains the fallback for a market with
        // no configured plan.
        // The back of the document, when Go has asked for it. Checked first:
        // by this point side 1 is submitted and the document stage counts as
        // completed, so the stage-based check below would read the capture as
        // the selfie and overwrite the wrong element.
        if (lastInstructionsByInstance.getOrDefault(instanceId, List.of()).stream()
                        .anyMatch("Side2Required"::equalsIgnoreCase)
                || outstanding.contains("PrimaryDocument/side2Image")) {
            log.debug("Capture routed as DOCUMENT BACK: instance={} outstanding={}", instanceId, outstanding);
            Map<String, Object> back = new java.util.LinkedHashMap<>(data);
            back.remove("attachmentRef");
            back.put("documentBack", ref);
            return back;
        }

        // The collects-preferred list, not the raw `outstanding` above: a
        // document (or other capture) whose parent is optional only ever
        // appears in collects, so classifying against raw outstanding alone
        // always misses it and misroutes the capture as a selfie.
        List<String> collectable = lastCollectableByInstance.getOrDefault(instanceId, outstanding);
        Boolean capturingDocument = mapper.currentCaptureIsDocument(collectable, completedStages(instanceId));
        boolean document = capturingDocument != null
                ? capturingDocument
                : collectable.stream().anyMatch(o -> o.startsWith("PrimaryDocument/"));

        log.debug("Capture routed as {}: instance={} completed={} outstanding={}",
                document ? "DOCUMENT" : "SELFIE", instanceId, completedStages(instanceId), outstanding);

        Map<String, Object> rewritten = new java.util.LinkedHashMap<>(data);
        rewritten.remove("attachmentRef");
        rewritten.put(document ? "documentImage" : "selfieImage", ref);
        return rewritten;
    }

    private List<String> fetchOutstanding(String instanceId) {
        GoInteractionFetchResponse response = call(token -> client.post()
                .uri("journey/interaction/fetch")
                .header("Authorization", "Bearer " + token)
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

    private <T> T call(Function<String, T> request) {
        return call(request, Map.of());
    }

    /**
     * Runs {@code request} with the current access token and translates Go's
     * failures into this service's own envelope.
     *
     * A 401/403 is retried once with a freshly minted token: without that, a
     * token Go has stopped accepting (revoked, rotated early, clock skew)
     * stays cached and every call fails until its expiry — up to an hour
     * under client_credentials.
     *
     * @param fieldsByGoPath for a submit, where each submitted field landed in
     *                       the request (see {@link GoInteractionSubmitRequest#fieldsByGoPath}),
     *                       so a 400/422 can name the fields Go rejected.
     */
    private <T> T call(Function<String, T> request, Map<String, String> fieldsByGoPath) {
        try {
            String token = tokenService.accessToken();
            try {
                return request.apply(token);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status != 401 && status != 403) {
                    throw e;
                }
                log.warn("GBG Go rejected the access token ({}); minting a new one and retrying once", status);
                tokenService.invalidate(token);
                return request.apply(tokenService.accessToken());
            }
        } catch (RestClientResponseException e) {
            HttpStatusCode statusCode = e.getStatusCode();
            String goDetail = describeGoError(e);
            log.warn("GBG Go call failed: {} {}", statusCode, goDetail);
            if (statusCode.value() == 404 || statusCode.value() == 410) {
                throw OnboardingException.sessionExpired("Your session has ended. Start again to continue.");
            }
            if (statusCode.value() == 400 || statusCode.value() == 422) {
                Map<String, String> fields = rejectedFields(goDetail, fieldsByGoPath);
                throw OnboardingException.validationFailed("Some of the details you entered could not be verified.",
                        fields.isEmpty() ? null : fields);
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

    /**
     * Submitted field → Go's message, for every {@code path: message}
     * segment in {@code goDetail} whose path is one this submit sent. Paths it
     * didn't send (or a body that isn't in this shape) are left out, so the
     * caller falls back to a screen-level message.
     */
    static Map<String, String> rejectedFields(String goDetail, Map<String, String> fieldsByGoPath) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (goDetail == null || fieldsByGoPath.isEmpty()) {
            return fields;
        }
        for (String segment : goDetail.split(";\\s*")) {
            int colon = segment.indexOf(": ");
            if (colon <= 0) continue;
            String field = fieldsByGoPath.get(segment.substring(0, colon).trim());
            if (field != null) {
                fields.putIfAbsent(field, segment.substring(colon + 2).trim());
            }
        }
        return fields;
    }
}
