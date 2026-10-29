package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.CallsGoCondition;
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
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

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
@Conditional(CallsGoCondition.class)
public class GoApiClient implements GoClient {

    private static final Logger log = LoggerFactory.getLogger(GoApiClient.class);

    /** The journey/start header that names the test scenario a run uses. */
    static final String SCENARIO_HEADER = "x-gbg-scenario";

    /** GBG Go's rule for a test scenario name. Checked before a name goes into a header. */
    private static final Pattern SCENARIO_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final RestClient client;
    private final GoTokenService tokenService;
    private final DefaultInteractionMapper mapper;
    private final AppConfigProperties appConfig;

    /** go.mode=test-scenarios: start every journey on a named test scenario. */
    private final boolean testScenarios;
    /** The scenario a journey starts on when the front end names none; null if not configured. */
    private final String defaultScenario;

    /**
     * Last outstanding-elements list seen per Go instance, so a capture submit
     * can tell document from selfie without an extra fetch. Bounded and
     * access-ordered (evicts the least-recently-used entry once full) rather
     * than a plain unbounded map — nothing here ever removes an instance on
     * session expiry or journey completion, so an unbounded cache would grow
     * for the life of the process against a long-running live-mode deployment.
     */
    private static final int MAX_CACHED_INSTANCES = 10_000;

    /** A per-instance cache: synchronized, access-ordered, evicting the least recently used past the bound. */
    private static <V> Map<String, V> boundedLru() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_CACHED_INSTANCES;
            }
        });
    }

    private final Map<String, List<String>> lastOutstandingByInstance = boundedLru();

    /**
     * Last instructions seen per Go instance, so a capture submit can tell the
     * back of a document from the front without an extra fetch — the same
     * reason {@link #lastOutstandingByInstance} exists, and populated from the
     * same response. {@code Side2Required} is the signal that matters.
     */
    private final Map<String, List<String>> lastInstructionsByInstance = boundedLru();

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
    private final Map<String, List<String>> lastCollectableByInstance = boundedLru();

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
    private final Map<String, java.util.Set<String>> completedStagesByInstance = boundedLru();

    /**
     * The test scenario each test run started on, by instance — so the
     * record can say which scenario produced its outcome. Same bound as the
     * caches above.
     */
    private final Map<String, String> testScenarioByInstance = boundedLru();

    public GoApiClient(GoProperties properties, GoTokenService tokenService,
                        DefaultInteractionMapper mapper, AppConfigProperties appConfig, RestClient.Builder builder) {
        this.tokenService = tokenService;
        this.mapper = mapper;
        this.appConfig = appConfig;
        this.client = builder.baseUrl(properties.baseUrl()).build();
        this.testScenarios = properties.testScenarios();
        String configured = properties.testScenario() == null || properties.testScenario().isBlank()
                ? null : properties.testScenario().trim();
        if (testScenarios && configured != null && !SCENARIO_NAME.matcher(configured).matches()) {
            throw new IllegalStateException("go.test-scenario \"" + configured + "\" is not a valid test scenario name: "
                    + "use letters, numbers, _ and -, up to 64 characters.");
        }
        this.defaultScenario = configured;
        if (testScenarios) {
            log.info("Test scenarios mode: journeys start on {} unless the front end names one with ?mock_scenario=",
                    defaultScenario == null ? "no default scenario (go.test-scenario is not set)" : "scenario '" + defaultScenario + "'");
        }
    }

    /**
     * The test scenario this journey starts on: the front end's
     * {@code ?mock_scenario=} when it names one, otherwise the market's
     * {@code go.test-scenario}. Null outside test-scenarios mode.
     *
     * A name that isn't a valid scenario name is refused rather than swapped
     * for the default: the default is usually a pass scenario, and a tester
     * who asked for the refer path and quietly got an approval would conclude
     * the refer path works.
     */
    String scenarioFor(String scenarioHint) {
        if (!testScenarios) return null;
        if (scenarioHint == null || scenarioHint.isBlank()) {
            if (defaultScenario == null) {
                log.error("Test scenarios mode, but no scenario to start on: set go.test-scenario for this market "
                        + "or open the app with ?mock_scenario=<scenario-name>");
                throw OnboardingException.upstreamUnavailable("This test journey has no test scenario configured.");
            }
            return defaultScenario;
        }
        String named = scenarioHint.trim();
        if (!SCENARIO_NAME.matcher(named).matches()) {
            // The value itself is not logged: it comes straight from the browser.
            log.warn("Refusing ?mock_scenario=: not a valid test scenario name (letters, numbers, _ and -, up to 64)");
            throw OnboardingException.badRequest("?mock_scenario= is not a valid test scenario name: use letters, "
                    + "numbers, _ and -, up to 64 characters.");
        }
        return named;
    }

    /** A {@code path: message} segment naming something the start request's context carried, i.e. the prefill. */
    private static final Pattern CONTEXT_PROBLEM = Pattern.compile("(^|;\\s*)context\\.[^:;]*: ");

    /**
     * How a refused journey/start reaches the customer. There is no session
     * yet, so {@code call()}'s defaults mislead here: a 404 would say "Your
     * session has ended", and on a test scenario a 400 or 422 would show as a
     * field error on a screen the customer hasn't filled in. We don't know
     * which of the three Go uses for a scenario it can't run, so all three are
     * covered — except a 400/422 naming a {@code context.} path, which is Go
     * rejecting the prefill and stays a field error.
     */
    private Refusal startRefusal(String scenario) {
        return (status, goDetail) -> {
            if (scenario != null && status == 403) {
                log.warn("GBG Go answered journey/start on test scenario '{}' with 403, even with a fresh token. "
                        + "Test scenarios may not be enabled for this organisation.", scenario);
                return null;
            }
            boolean scenarioRefused = scenario != null && (status == 400 || status == 422)
                    && !CONTEXT_PROBLEM.matcher(goDetail == null ? "" : goDetail).find();
            if (status == 404 && scenario == null) {
                log.error("GBG Go answered journey/start with 404. Check this market's app.resource-id names "
                        + "a published journey.");
                return OnboardingException.upstreamUnavailable("Could not start your verification. Try again shortly.");
            }
            if (status == 404 || scenarioRefused) {
                log.error("GBG Go refused journey/start on test scenario '{}' ({}): {}. Check the scenario is "
                        + "saved on this journey (Journey builder → Test Scenarios) and its outcome names "
                        + "match the journey's exactly. A 404 can also mean the journey itself doesn't exist: "
                        + "check this market's app.resource-id.", scenario, status, goDetail);
                return OnboardingException.upstreamUnavailable("This test journey could not start on its test scenario.");
            }
            return null;
        };
    }

    /**
     * In test-scenarios mode the start request carries the
     * {@code x-gbg-scenario} header, and only the start request: Go ties the
     * scenario to the instance, so the fetches and submits after it are
     * unchanged.
     */
    @Override
    public GoStartResult startJourney(String resourceId, Map<String, Object> prefill, String scenarioHint) {
        String scenario = scenarioFor(scenarioHint);
        GoStartResponse started = call(token -> {
            RestClient.RequestBodySpec request = client.post()
                    .uri("journey/start")
                    .header("Authorization", "Bearer " + token);
            if (scenario != null) {
                request = request.header(SCENARIO_HEADER, scenario);
            }
            return request.body(GoStartRequest.of(resourceId, prefill))
                    .retrieve()
                    .body(GoStartResponse.class);
        }, Map.of(), startRefusal(scenario));
        if (started == null || started.instanceId() == null) {
            throw OnboardingException.upstreamUnavailable("Could not start your verification. Try again shortly.");
        }
        if (scenario != null) {
            // Asked for a test run but Go did not confirm one. Only Go's reply
            // to journey/start can say whether the scenario took, so by now the
            // instance exists, with whatever prefill the start carried. What
            // stopping here prevents is everything after: no fetch, no submit,
            // so the customer's details and captures never reach a journey
            // that would check them for real. The instance ID is logged so it
            // can be found in GBG Go.
            if (!started.isTestRun()) {
                log.error("Asked GBG Go for test scenario '{}' but instance {} did not start as a test run "
                        + "(test: {}). Abandoning it before any interaction, so no details reach it.",
                        scenario, started.instanceId(), started.test());
                throw OnboardingException.upstreamUnavailable("This test journey could not start on its test scenario.");
            }
            // A run on some other scenario is the silent swap scenarioFor
            // refuses to make: its outcomes would be read as this scenario's.
            if (started.scenario() != null && !started.scenario().equals(scenario)) {
                log.error("Asked GBG Go for test scenario '{}' but instance {} started on scenario '{}'. "
                        + "Abandoning it before any interaction.", scenario, started.instanceId(), started.scenario());
                throw OnboardingException.upstreamUnavailable("This test journey could not start on its test scenario.");
            }
            testScenarioByInstance.put(started.instanceId(), scenario);
            log.info("Started test run {} on scenario '{}': modules return the scenario's outcomes, no live checks",
                    started.instanceId(), scenario);
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
        // Always overwritten, empty when Go omits the field: keeping the last
        // non-null value let a stale Side2Required outlive the document
        // screen and route the selfie into documents[0].side2Image.
        lastOutstandingByInstance.put(instanceId, response.outstanding() == null ? List.of() : response.outstanding());
        lastInstructionsByInstance.put(instanceId, response.instructions() == null ? List.of() : response.instructions());
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
        //    so it reports COMPLETED carrying a `fail` decision. A result.status
        //    of error counts the same, whatever the journey status says: Go
        //    documents it as authoritative, and an abandoned journey will not
        //    move again;
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
                || DefaultInteractionMapper.resultFailed(response.result())
                || decided
                ? JourneyStatus.COMPLETED
                : JourneyStatus.IN_PROGRESS;
        return new StateResponse(status, asRecord.decision(), asRecord.moduleRuns());
    }

    /**
     * A test run's record says so, with the scenario that produced it, so a
     * scenario's outcome is never mistaken for a real verification. A summary
     * row, so neither the response shape nor the front end changes.
     */
    @Override
    public RecordResponse fetchRecord(String instanceId) {
        RecordResponse record = mapper.toRecord(fetchGoState(instanceId));
        String scenario = testScenarioByInstance.get(instanceId);
        if (scenario == null && !GoStartResponse.isTestInstance(instanceId)) {
            return record;
        }
        List<SummaryRow> summary = new java.util.ArrayList<>(record.summary() == null ? List.of() : record.summary());
        summary.add(0, new SummaryRow("Test run", scenario == null ? "Yes" : "Yes, scenario " + scenario));
        return record.withSummary(List.copyOf(summary));
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

        List<String> outstanding = lastOutstandingByInstance.get(instanceId);
        if (outstanding == null) {
            // Nothing cached (e.g. just after a restart): a full fetch fills
            // the outstanding, instructions and collectable caches together,
            // since all three are read below.
            fetchInteraction(instanceId);
            outstanding = lastOutstandingByInstance.getOrDefault(instanceId, List.of());
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

    private <T> T call(Function<String, T> request, Map<String, String> fieldsByGoPath) {
        return call(request, fieldsByGoPath, (status, goDetail) -> null);
    }

    /** A caller's own reading of a status Go refused with; null leaves it to {@code call()}'s defaults. */
    @FunctionalInterface
    private interface Refusal {
        OnboardingException classify(int status, String goDetail);
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
     * @param refusal        consulted first once Go has refused the call, after the token retry.
     */
    private <T> T call(Function<String, T> request, Map<String, String> fieldsByGoPath, Refusal refusal) {
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
            OnboardingException classified = refusal.classify(statusCode.value(), goDetail);
            if (classified != null) {
                throw classified;
            }
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
