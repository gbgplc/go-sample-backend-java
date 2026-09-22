package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.operations.Collect1;
import com.gbg.gocore.models.operations.Collect2;
import com.gbg.gocore.models.operations.CollectUnion;
import com.gbg.gocore.models.operations.Config;
import com.gbg.gocore.models.operations.Delivery;
import com.gbg.gocore.models.operations.GetJourneyStateClassification;
import com.gbg.gocore.models.operations.GetJourneyStateDocument;
import com.gbg.gocore.models.operations.GetJourneyStateResponseBody;
import com.gbg.gocore.models.operations.Participant;
import com.gbg.gocore.models.operations.ResponseBody1;
import com.gbg.gocore.models.operations.StartJourneyContext;
import com.gbg.gocore.models.operations.StartJourneyRequest;
import com.gbg.gocore.models.operations.StartJourneySubject;
import com.gbg.gocore.models.operations.SubmitInteractionBiometric4;
import com.gbg.gocore.models.operations.SubmitInteractionBiometricUnion;
import com.gbg.gocore.models.operations.SubmitInteractionConsent;
import com.gbg.gocore.models.operations.SubmitInteractionContext;
import com.gbg.gocore.models.operations.SubmitInteractionDocument;
import com.gbg.gocore.models.operations.SubmitInteractionIdentity;
import com.gbg.gocore.models.operations.SubmitInteractionIdentityCurrentAddress;
import com.gbg.gocore.models.operations.SubmitInteractionIdentityEmail;
import com.gbg.gocore.models.operations.SubmitInteractionIdentityIdNumber;
import com.gbg.gocore.models.operations.SubmitInteractionIdentityPhone;
import com.gbg.gocore.models.operations.SubmitInteractionIdentityPreviousAddress;
import com.gbg.gocore.models.operations.SubmitInteractionRequest;
import com.gbg.gocore.models.operations.SubmitInteractionSubject;
import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.FieldSchema;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ModuleRun;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.StagePlanEntry;
import com.gbg.samples.onboarding.api.dto.StageState;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the SDK's typed interaction/state responses into the front end's
 * opinionated {@code Interaction}/{@code RecordResponse} DTOs, and turns the
 * front end's flat submission map into the SDK's typed submit request. The
 * go-core-sdk sibling of api-implementation's {@code DefaultInteractionMapper}
 * — every piece of screen-selection logic below (the plan-driven stage
 * selection, the document/side-2 handling, the outcome classification) is
 * ported structurally unchanged; only the Go-response-shaped inputs differ.
 *
 * <h2>What actually changed shape, per the spike (see
 * {@code docs/sdk-jar-inspection/FINDINGS.md})</h2>
 * <ul>
 *   <li>{@code collects} is now a typed {@code List<CollectUnion>}
 *       (wrapping {@code Collect1}/{@code Collect2}: {@code ref}/{@code spec}/
 *       {@code recommended}, {@code Collect2} adding a combinator/inputs pair
 *       not used by anything this mapper reads) rather than a raw
 *       {@code List<Map<String,Object>>} — same {@code ref}/{@code spec}
 *       fields, same "required" semantics, just accessed through generated
 *       accessors instead of map lookups.</li>
 *   <li>{@code journeys().getState()}'s {@code GetJourneyStateResponseBody}
 *       has <b>no</b> top-level {@code result} field and its typed
 *       {@code context} wraps only {@code subject} (the extracted identity/
 *       document/biometric domain model) — there is no typed home anywhere in
 *       the generated model for per-module timing
 *       ({@code durationMilliSec}/{@code endedAt}), per-module
 *       outcome/outcomeClassification, or the journey-level decision
 *       classification. This was flagged in FINDINGS.md Q3 as unverified, and
 *       verified live 2026-09-18: the SDK's shared {@code ObjectMapper}
 *       ({@code com.gbg.gocore.utils.JSON}) is configured with
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES = false}, so the wire JSON's
 *       top-level {@code steps} and {@code result} keys — which the old
 *       raw-HTTP {@code GoStateResponse} record declared and read directly —
 *       have no field to land in on this generated class and are silently
 *       discarded. They never reach {@code data} either: a live capture of
 *       every {@code journey/state/fetch} response for a real instance came
 *       back {@code data={}} every time, {@code context} populated. This is
 *       a genuine gap in the SDK's current (alpha) response model for this
 *       operation, not a wrong guess about where to look.
 *
 *       <p>The fix ({@link GoSdkClient}, via {@link RawStateBodyCapturingHttpClient})
 *       buffers the exact wire bytes of the {@code journey/state/fetch}
 *       response as the SDK's own {@link com.gbg.gocore.utils.SpeakeasyHTTPClient}
 *       sends them — no separate network call — and parses that buffer
 *       itself with a plain {@code ObjectMapper}, the same way
 *       {@code GoStateResponse} used to. {@link #toRecord} and
 *       {@link #carriesRealResult} both now take that raw parsed map as an
 *       explicit parameter (named {@code rawStateBody}) instead of reading
 *       {@code body.data()}, which is confirmed to always be empty for this
 *       operation and kept only as the last-resort fallback it always
 *       was.</li>
 *   <li>Document classification has a genuine typed alternative
 *       ({@code context.subject.documents[0].classification}) with
 *       {@code category}/{@code type}/{@code subtype}/{@code countryName}/
 *       {@code year} fields — but no single {@code name} string matching the
 *       old raw JSON's {@code classification.name}. {@link #documentTypeLabel}
 *       tries composing a label from the typed fields first and falls back to
 *       walking {@code data} for a raw {@code classification.name}-shaped
 *       value, exactly per the task's "defensive, both paths" instruction.</li>
 * </ul>
 */
@Component
public class SdkInteractionMapper {

    private static final Logger log = LoggerFactory.getLogger(SdkInteractionMapper.class);

    /** Same as DefaultInteractionMapper — Go's own generic Data Verification element names, not per-market copy. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("FullName/firstName", "First name"),
            Map.entry("FullName/lastNames", "Last name"),
            Map.entry("DateOfBirth", "Date of birth"),
            Map.entry("CurrentAddress/building", "Building name or number"),
            Map.entry("CurrentAddress/thoroughfare", "Street"),
            Map.entry("CurrentAddress/locality", "Town or city"),
            Map.entry("CurrentAddress/postalCode", "Postcode"),
            Map.entry("CurrentAddress/country", "Country"),
            Map.entry("MobilePhone/number", "Mobile number"),
            Map.entry("LandlinePhone/number", "Landline number"),
            Map.entry("PersonalEmail/email", "Personal email"),
            Map.entry("WorkEmail/email", "Work email"),
            Map.entry("MothersMaidenName", "Mother's maiden name"),
            Map.entry("NationalInsuranceNumber", "National Insurance number"),
            Map.entry("Gender", "Gender"),
            Map.entry("SSN", "Social Security number"),
            Map.entry("PreviousAddresses", "Previous address")
    );

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("CurrentAddress/postalCode", "postcode"),
            Map.entry("DateOfBirth", "date"),
            Map.entry("MobilePhone/number", "tel"),
            Map.entry("PersonalEmail/email", "email"),
            Map.entry("WorkEmail/email", "email")
    );

    private static final Map<String, String> HELPER_TEXT = Map.ofEntries(
            Map.entry("CurrentAddress/country", "Three-letter country code, e.g. GBR")
    );

    private static final Map<String, String> PLACEHOLDERS = Map.ofEntries(
            Map.entry("CurrentAddress/country", "GBR"),
            Map.entry("CurrentAddress/postalCode", "SW1A 2AA")
    );

    private static final Set<String> POSITIVE_OUTCOMES = Set.of(
            "document classified", "extraction successful", "success", "match"
    );

    private final ScreenPlanProperties screenPlan;

    public SdkInteractionMapper(ScreenPlanProperties screenPlan) {
        this.screenPlan = screenPlan;
    }

    // ------------------------------------------------------------------
    // journeys().start() request building
    // ------------------------------------------------------------------

    /**
     * Builds the SDK's {@code StartJourneyRequest}.
     *
     * <b>Judgment call, unverified</b>: the raw-HTTP client nested the
     * prefill map at {@code context.subject} — an untyped location in the old
     * wire shape. The SDK's {@code StartJourneyContext.subject} is now fully
     * typed ({@code StartJourneyIdentity}/{@code StartJourneyDocument}/...),
     * so an arbitrary caller-supplied {@code Map<String,Object>} can no longer
     * be dropped there generically. {@code StartJourneyRequest} does carry a
     * separate untyped {@code data} field alongside {@code context} — spike
     * FINDINGS.md (Q2) reads that as "the subject-prefill map, untyped", so
     * that is where {@code prefill} is sent here, with an empty typed
     * {@code subject} and {@code config.delivery=API} (confirmed to exist and
     * match the raw request's {@code delivery: "api"} exactly — see
     * {@code Config}/{@code Delivery} in the SDK source). This has not been
     * exercised against a live journey; if prefill silently has no effect,
     * this is the mapping to revisit first.
     */
    public StartJourneyRequest toStartRequest(String resourceId, Map<String, Object> prefill) {
        StartJourneyContext context = new StartJourneyContext(
                new Config(Delivery.API),
                new StartJourneySubject());
        return new StartJourneyRequest(resourceId, context,
                prefill == null || prefill.isEmpty() ? null : prefill);
    }

    // ------------------------------------------------------------------
    // interactions().fetch() -> Interaction
    // ------------------------------------------------------------------

    /**
     * The next screen, for a journey with no stages submitted yet. Equivalent
     * to {@code toInteraction(body, Set.of())} — see the two-argument form's
     * javadoc for why a journey in flight must use it instead.
     */
    public Interaction toInteraction(ResponseBody1 body) {
        return toInteraction(body, Set.of());
    }

    /**
     * The next screen, given a full interaction body ({@code ResponseBody1} —
     * present whenever Go has something to show, as opposed to
     * {@code ResponseBody2}'s "still working, nothing to render yet" stub,
     * handled separately by {@link #processingInteraction(String)}).
     *
     * @param completed stage names already submitted on this journey; never null.
     */
    public Interaction toInteraction(ResponseBody1 body, Set<String> completed) {
        String goStatus = body.journey().status().value();
        String interactionId = body.interactionId() != null ? body.interactionId() : body.instanceId();

        if (isFailed(goStatus)) {
            return failedInteraction(interactionId);
        }

        List<String> outstandingRaw = body.outstanding().orElse(List.of());
        if (awaitingManualReview(outstandingRaw)) {
            return manualReviewInteraction(interactionId);
        }

        // No `processing` flag on ResponseBody1 (only ResponseBody2 carries
        // one) — a real interaction body means there is something to collect
        // or the journey is otherwise pending customer input, so `false` here
        // reproduces the raw mapper's behaviour exactly (mapStatus falls
        // through "InProgress" + processing=false to PENDING_INPUT, which is
        // what lets the code below pick a screen from `outstanding`/`collects`).
        JourneyStatus status = mapStatus(goStatus, false);
        if (status == JourneyStatus.COMPLETED) {
            // ResponseBody1 carries no decision (`result`) at all — that only
            // ever arrives via journeys().getState(). Rather than guess at a
            // decision here (or fabricate a RESULT screen with a null
            // decision, which GoSdkClient.statusFrom would read back as
            // PENDING_INPUT anyway — see GoApiClient's original comment on
            // that same fallback), defer to a processing screen and let the
            // front end's normal `/state` poll (GoSdkClient.fetchState ->
            // journeys().getState()) surface the real decision. This is a
            // real, deliberate behaviour difference from api-implementation,
            // which could sometimes short-circuit straight to a RESULT screen
            // from the interaction fetch itself — see this module's README,
            // "Known gaps".
            return processingInteraction(interactionId);
        }

        List<String> collectable = collectableRefs(body);
        List<String> outstanding = collectable.isEmpty() ? outstandingRaw : collectable;
        Set<String> requiredRefs = requiredRefs(body);
        List<String> instructions = body.instructions().orElse(List.of());

        if (outstanding.isEmpty()) {
            return processingInteraction(interactionId);
        }

        Optional<ScreenPlanProperties.Stage> documentStage = screenPlan.stages().stream()
                .filter(stage -> stage.claimsRef("PrimaryDocument/side1Image"))
                .findFirst();
        boolean side1Submitted = documentStage.isPresent() && completed.contains(documentStage.get().name());

        if (documentStage.isPresent() && side1Submitted) {
            if (side2Required(outstandingRaw, instructions)) {
                return toStagedInteraction(interactionId, documentStage.get().asSecondSide(),
                        outstanding, completed, requiredRefs);
            }
            if (side2Undecided(instructions)) {
                return processingInteraction(interactionId, "Verifying document type",
                        "This usually takes a few seconds.");
            }
        }

        boolean planConfigured = !screenPlan.stages().isEmpty();
        boolean everyStageSubmitted = planConfigured && screenPlan.stages().stream()
                .allMatch(stage -> completed.contains(stage.name()));
        if (everyStageSubmitted) {
            return processingInteraction(interactionId);
        }

        List<String> finalOutstanding = outstanding;
        return screenPlan.stages().stream()
                .filter(stage -> !completed.contains(stage.name()))
                .filter(stage -> stage.claims(finalOutstanding) || stage.alwaysCollects())
                .findFirst()
                .map(stage -> toStagedInteraction(interactionId, stage, finalOutstanding, completed, requiredRefs))
                .orElseGet(() -> unmappedElementsInteraction(interactionId, finalOutstanding));
    }

    /** The processing screen for a {@code ResponseBody2} ("still working, no interaction yet") fetch result. */
    public Interaction processingInteraction(String interactionId) {
        return processingInteraction(interactionId, "Running Identity Verification", "This usually takes a few seconds.");
    }

    private Interaction processingInteraction(String interactionId, String title, String body) {
        return new Interaction(
                interactionId, ScreenKind.PROCESSING, "Processing", null, title,
                body, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );
    }

    private Interaction failedInteraction(String interactionId) {
        return new Interaction(
                interactionId, ScreenKind.RESULT, "Decision", null,
                "We could not complete your verification",
                "Something went wrong while we were checking your details. No decision was reached.",
                null, "Done", null, null, null, null, null, null, null, null,
                Decision.FAIL, null, List.of(), null, null
        );
    }

    private Interaction manualReviewInteraction(String interactionId) {
        return new Interaction(
                interactionId, ScreenKind.RESULT, "Decision", null,
                "With our team",
                "Someone is reviewing your details. We will be in touch.",
                null, "Done", null, null, null, null, null, null, null, null,
                Decision.REFER, null, List.of(), null, null
        );
    }

    private Interaction unmappedElementsInteraction(String interactionId, List<String> outstanding) {
        log.warn("No screen mapped for outstanding elements {} — falling back to a generic form", outstanding);
        List<FieldSchema> fields = outstanding.stream()
                .map(ref -> FieldSchema.of(ref, label(ref), null))
                .toList();
        return new Interaction(
                interactionId, ScreenKind.FORM, "More details", null, "A few more details",
                "We need a bit more information to continue.", null, "Continue", null, null, null,
                fields, null, null, null, null, null, null, null, null, null
        );
    }

    private Interaction toStagedInteraction(String interactionId, ScreenPlanProperties.Stage stage,
                                             List<String> outstanding, Set<String> completed,
                                             Set<String> requiredRefs) {
        boolean reachedCurrent = false;
        List<StagePlanEntry> rail = new ArrayList<>();
        for (ScreenPlanProperties.Stage planStage : screenPlan.stages()) {
            StageState state;
            if (planStage.stage().equals(stage.stage())) {
                state = StageState.ACTIVE;
                reachedCurrent = true;
            } else if (reachedCurrent) {
                state = StageState.UPCOMING;
            } else {
                boolean submitted = completed.contains(planStage.name());
                boolean satisfied = !planStage.alwaysCollects() && !planStage.claims(outstanding);
                state = submitted || satisfied ? StageState.DONE : StageState.UPCOMING;
            }
            rail.add(new StagePlanEntry(planStage.stage(), state));
        }

        return new Interaction(
                interactionId,
                stage.kind(),
                stage.stage(),
                null,
                stage.title(),
                stage.body(),
                null,
                stage.cta(),
                null,
                stage.captureType(),
                stage.accepted(),
                stage.kind() == ScreenKind.FORM ? fieldsFor(stage, outstanding, requiredRefs) : null,
                null,
                stage.kind() == ScreenKind.CONSENT ? screenPlan.consentChecks() : null,
                stage.modules(),
                null, null, null, null, null,
                rail
        );
    }

    private List<FieldSchema> fieldsFor(ScreenPlanProperties.Stage stage, List<String> outstanding,
                                         Set<String> requiredRefs) {
        List<String> claimed = outstanding.stream()
                .filter(stage::claimsRef)
                .toList();
        List<String> required = claimed.stream().filter(requiredRefs::contains).toList();
        List<String> shown = required.isEmpty() ? claimed : required;

        return shown.stream()
                .map(o -> new FieldSchema(o, label(o), TYPES.get(o), PLACEHOLDERS.get(o),
                        HELPER_TEXT.get(o), requiredRefs.isEmpty() || requiredRefs.contains(o)))
                .toList();
    }

    /** Same contract as DefaultInteractionMapper's — used by GoSdkClient's resolveAttachment(). */
    public Boolean currentCaptureIsDocument(List<String> outstanding, Set<String> completed) {
        List<String> listed = outstanding == null ? List.of() : outstanding;
        return screenPlan.stages().stream()
                .filter(stage -> !completed.contains(stage.name()))
                .filter(stage -> stage.claims(listed) || stage.alwaysCollects())
                .filter(stage -> stage.kind() == ScreenKind.CAPTURE || stage.kind() == ScreenKind.UPLOAD)
                .findFirst()
                .map(stage -> "document".equalsIgnoreCase(stage.captureType())
                        || stage.prefix().startsWith("PrimaryDocument"))
                .orElse(null);
    }

    /** Same contract as DefaultInteractionMapper's — used by GoSdkClient.submitInteraction(). */
    public String stageFor(java.util.Collection<String> submittedKeys, Set<String> completed) {
        if (submittedKeys == null || submittedKeys.isEmpty()) {
            return screenPlan.stages().stream()
                    .filter(s -> !completed.contains(s.name()))
                    .filter(s -> s.kind() == ScreenKind.FORM)
                    .map(ScreenPlanProperties.Stage::name)
                    .findFirst()
                    .orElse(null);
        }
        List<ScreenPlanProperties.Stage> remaining = screenPlan.stages().stream()
                .filter(s -> !completed.contains(s.name()))
                .toList();

        for (ScreenPlanProperties.Stage stage : remaining) {
            for (String key : submittedKeys) {
                if (stage.claimsRef(key)) {
                    return stage.name();
                }
            }
        }

        for (ScreenPlanProperties.Stage stage : remaining) {
            String element = stage.prefix().endsWith("/")
                    ? stage.prefix().substring(0, stage.prefix().length() - 1)
                    : stage.prefix();
            for (String key : submittedKeys) {
                if (key == null) continue;
                boolean selfie = "Selfie".equals(element) && key.toLowerCase(Locale.ROOT).contains("selfie");
                boolean document = "PrimaryDocument".equals(element) && key.toLowerCase(Locale.ROOT).contains("document");
                boolean consent = "Consent".equals(element) && key.toLowerCase(Locale.ROOT).contains("consent");
                if (selfie || document || consent) {
                    return stage.name();
                }
            }
        }

        return remaining.stream()
                .filter(s -> s.kind() == ScreenKind.CONSENT)
                .map(ScreenPlanProperties.Stage::name)
                .findFirst()
                .orElse(null);
    }

    private static boolean awaitingManualReview(List<String> outstanding) {
        return outstanding != null
                && outstanding.stream().anyMatch("ManualReviewDecision"::equalsIgnoreCase);
    }

    private static boolean side2Required(List<String> outstanding, List<String> instructions) {
        if (outstanding != null && outstanding.contains("PrimaryDocument/side2Image")) {
            return true;
        }
        return instructions != null && instructions.stream().anyMatch("Side2Required"::equalsIgnoreCase);
    }

    private static boolean side2Undecided(List<String> instructions) {
        return instructions != null
                && instructions.stream().anyMatch("LazySide2CollectionRequired"::equalsIgnoreCase);
    }

    private static String label(String ref) {
        String known = LABELS.get(ref);
        if (known != null) return known;
        String leaf = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
        String spaced = leaf.replaceAll("(?<!^)(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    static boolean isFailed(String goStatus) {
        return "Failed".equalsIgnoreCase(goStatus) || "Error".equalsIgnoreCase(goStatus);
    }

    private JourneyStatus mapStatus(String goStatus, boolean processing) {
        if ("Completed".equalsIgnoreCase(goStatus)) return JourneyStatus.COMPLETED;
        if (processing || "Paused".equalsIgnoreCase(goStatus)) return JourneyStatus.IN_PROGRESS;
        return JourneyStatus.PENDING_INPUT;
    }

    /**
     * {@code journeys().getState()}'s {@code ResponseBody2}-equivalent path:
     * a fetch that returned no interaction body at all, just a status and
     * (maybe) a processing flag. Public so {@link GoSdkClient} can call it
     * directly for that union branch without duplicating the mapStatus logic.
     */
    public Interaction processingOrTerminalWithoutInteraction(String interactionId, String goStatus, boolean processing) {
        // Whatever mapStatus(goStatus, processing) would say — Completed,
        // Failed, or genuinely still running — this response shape carries no
        // decision payload at all (no `result`, unlike the raw HTTP client's
        // GoInteractionFetchResponse). Every branch defers to a processing
        // screen and lets the front end's /state poll (GoSdkClient.fetchState
        // -> journeys().getState(), which does carry a decision) surface the
        // actual terminal outcome. See this class's javadoc and this module's
        // README "Known gaps" for why this is a deliberate behaviour
        // difference from api-implementation.
        return processingInteraction(interactionId);
    }

    // ------------------------------------------------------------------
    // collects() helpers
    // ------------------------------------------------------------------

    private static String refOf(CollectUnion union) {
        Optional<Collect1> c1 = union.collect1();
        if (c1.isPresent()) return c1.get().ref();
        Optional<Collect2> c2 = union.collect2();
        return c2.map(Collect2::ref).orElse(null);
    }

    private static boolean requiredOf(CollectUnion union) {
        Optional<Collect1> c1 = union.collect1();
        if (c1.isPresent()) return "required".equalsIgnoreCase(c1.get().spec().value());
        Optional<Collect2> c2 = union.collect2();
        return c2.map(c -> "required".equalsIgnoreCase(c.spec().value())).orElse(false);
    }

    /** Public so {@link GoSdkClient} can populate its collects-preferred cache from the same source. */
    public static List<String> collectableRefs(ResponseBody1 body) {
        return body.interaction().collects().stream()
                .map(SdkInteractionMapper::refOf)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static Set<String> requiredRefs(ResponseBody1 body) {
        return body.interaction().collects().stream()
                .filter(SdkInteractionMapper::requiredOf)
                .map(SdkInteractionMapper::refOf)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------
    // journeys().getState() -> RecordResponse
    // ------------------------------------------------------------------

    /**
     * @param rawStateBody the same {@code journey/state/fetch} response,
     *                      parsed independently from the raw wire bytes
     *                      ({@link RawStateBodyCapturingHttpClient}) — see
     *                      this class's javadoc for why {@code body.data()}
     *                      alone is not enough. Never null; pass {@code Map.of()}
     *                      if the raw bytes genuinely could not be captured.
     */
    public RecordResponse toRecord(GetJourneyStateResponseBody body, Map<String, Object> rawStateBody) {
        String goStatus = body.status().value();
        // body.data() is confirmed always empty for this operation (see class
        // javadoc) but costs nothing to prefer if the SDK ever starts
        // populating it — rawStateBody is the confirmed-working source.
        Map<String, Object> data = !rawStateBody.isEmpty() ? rawStateBody : body.data().orElse(Map.of());

        Decision decision = isFailed(goStatus) ? Decision.FAIL : mapDecision(rawResult(data));
        List<RawStep> steps = rawSteps(data);

        List<ModuleRun> moduleRuns = steps.stream()
                .filter(step -> step.name() != null)
                .map(step -> new ModuleRun(
                        step.name(),
                        mapModuleState(step),
                        formatModuleMs(step.durationMilliSec()),
                        step.resultOutcome()))
                .toList();

        String moduleAdvice = firstModuleErrorAction(steps);
        boolean systemError = decision == Decision.FAIL && moduleAdvice != null;

        String title = systemError
                ? "We could not run your checks"
                : switch (decision) {
                    case PASS -> "Verification complete";
                    case FAIL -> "We could not complete your verification";
                    case REFER -> "With our team";
                };

        String body_ = systemError
                ? moduleAdvice
                : switch (decision) {
                    case PASS -> "Your identity has been verified.";
                    case FAIL -> "We were not able to verify your identity from what you provided.";
                    case REFER -> "Someone is reviewing your details. We will be in touch.";
                };

        RawJourney journeyInfo = rawJourneyInfo(data);
        String startedAt = journeyInfo == null ? null : journeyInfo.startedAt();
        String endedAt = journeyInfo == null || journeyInfo.endedAt() == null
                ? latestStepEndedAt(steps)
                : journeyInfo.endedAt();
        String totalTime = formatDurationSeconds(startedAt, endedAt);

        List<SummaryRow> summary = new ArrayList<>();
        if (journeyInfo != null && journeyInfo.name() != null && !journeyInfo.name().isBlank()) {
            String journeyLabel = journeyInfo.version() == null || journeyInfo.version().isBlank()
                    ? journeyInfo.name()
                    : journeyInfo.name() + " · v" + journeyInfo.version();
            summary.add(new SummaryRow("Journey", journeyLabel));
        }
        if (body.instanceId() != null && !body.instanceId().isBlank()) {
            summary.add(new SummaryRow("Reference", body.instanceId()));
        }
        String startedLabel = formatTimestamp(startedAt);
        if (startedLabel != null) {
            summary.add(new SummaryRow("Started", startedLabel));
        }
        String endedLabel = formatTimestamp(endedAt);
        if (endedLabel != null) {
            summary.add(new SummaryRow("Decision reached", endedLabel));
        }
        if (totalTime != null) {
            summary.add(new SummaryRow("Total time", totalTime));
        }
        String documentLabel = documentTypeLabel(body, data);
        if (documentLabel != null) {
            summary.add(new SummaryRow("Document", documentLabel));
        }

        return new RecordResponse(
                decision,
                title,
                totalTime == null ? "" : totalTime,
                body_,
                systemError ? "Try again" : "Done",
                moduleRuns,
                List.copyOf(summary),
                null,
                systemError
        );
    }

    /**
     * Terminal check for {@code GoSdkClient.fetchState} — needs both the
     * typed {@code status} and the module-run list {@link #toRecord} already
     * derives from {@code data}, so it takes the same body plus the already-
     * computed {@code RecordResponse} rather than re-deriving moduleRuns
     * itself.
     */
    public boolean carriesRealResult(GetJourneyStateResponseBody body, Map<String, Object> rawStateBody) {
        Map<String, Object> data = !rawStateBody.isEmpty() ? rawStateBody : body.data().orElse(Map.of());
        RawResult result = rawResult(data);
        return result != null && result.outcomeClassification() != null;
    }

    // ------------------------------------------------------------------
    // Defensive `data`-map walking. See the class javadoc: GetJourneyState's
    // typed `context` has no field for any of this — it is read from the
    // untyped catch-all the same way the raw-HTTP client used to deserialize
    // its own JSON, because that is the only place left it can be.
    // ------------------------------------------------------------------

    private record RawStep(String name, String status, String outcome, String outcomeClassification,
                            Long durationMilliSec, String endedAt, String errorFirstAction) {
        String resultOutcome() {
            return outcome;
        }
    }

    private record RawResult(String outcome, String outcomeClassification) {
    }

    private record RawJourney(String name, String version, String startedAt, String endedAt) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Root-level `result`, mirroring the raw GoStateResponse.result() field. Not fully confirmed live — see class javadoc. */
    private static RawResult rawResult(Map<String, Object> data) {
        Map<String, Object> result = asMap(data.get("result"));
        if (result == null) return null;
        return new RawResult(asString(result.get("outcome")), asString(result.get("outcomeClassification")));
    }

    /** `steps`, falling back to `context.process.steps` — same dual path as the old GoStateResponse.allSteps(). */
    private static List<RawStep> rawSteps(Map<String, Object> data) {
        List<Object> raw = asList(data.get("steps"));
        if (raw == null || raw.isEmpty()) {
            Map<String, Object> context = asMap(data.get("context"));
            Map<String, Object> process = context == null ? null : asMap(context.get("process"));
            raw = process == null ? null : asList(process.get("steps"));
        }
        if (raw == null) return List.of();
        List<RawStep> steps = new ArrayList<>(raw.size());
        for (Object entry : raw) {
            Map<String, Object> step = asMap(entry);
            if (step == null) continue;
            String name = asString(step.get("name"));
            Map<String, Object> result = asMap(step.get("result"));
            String status = result == null ? null : asString(result.get("status"));
            String outcome = result == null ? null : asString(result.get("outcome"));
            String outcomeClassification = asString(step.get("outcomeClassification"));
            Map<String, Object> process = asMap(step.get("process"));
            Map<String, Object> detail = process == null ? null : asMap(process.get("step"));
            Long durationMilliSec = detail == null ? null : asLong(detail.get("durationMilliSec"));
            String endedAt = detail == null ? null : asString(detail.get("endedAt"));
            String errorFirstAction = null;
            Map<String, Object> error = result == null ? null : asMap(result.get("error"));
            if (error != null) {
                List<Object> errors = asList(error.get("errors"));
                if (errors != null && !errors.isEmpty()) {
                    Map<String, Object> firstError = asMap(errors.get(0));
                    errorFirstAction = firstError == null ? null : asString(firstError.get("action"));
                }
            }
            steps.add(new RawStep(name, status, outcome, outcomeClassification, durationMilliSec, endedAt, errorFirstAction));
        }
        return steps;
    }

    /** `journey`, falling back to `context.process.journey` — same dual path as the old GoStateResponse.journeyInfo(). */
    private static RawJourney rawJourneyInfo(Map<String, Object> data) {
        Map<String, Object> journey = asMap(data.get("journey"));
        if (journey == null) {
            Map<String, Object> context = asMap(data.get("context"));
            Map<String, Object> process = context == null ? null : asMap(context.get("process"));
            journey = process == null ? null : asMap(process.get("journey"));
        }
        if (journey == null) return null;
        return new RawJourney(asString(journey.get("name")), asString(journey.get("version")),
                asString(journey.get("startedAt")), asString(journey.get("endedAt")));
    }

    /**
     * The document type Document Classification reported. Tries the typed
     * {@code context.subject.documents[0].classification} first — composing a
     * label from its {@code countryName}/{@code type}/{@code subtype}/
     * {@code year} fields, the closest reconstruction of the raw single
     * {@code classification.name} string available from that shape — then
     * falls back to walking {@code data} for a raw {@code documents[0].
     * classification.name} value the way the original mapper read it.
     * Neither path is confirmed against a live populated response.
     */
    private String documentTypeLabel(GetJourneyStateResponseBody body, Map<String, Object> data) {
        String typed = typedDocumentTypeLabel(body);
        if (typed != null) return typed;
        return rawDocumentTypeLabel(data);
    }

    private String typedDocumentTypeLabel(GetJourneyStateResponseBody body) {
        try {
            var subjectOpt = body.context();
            if (subjectOpt.isEmpty()) return null;
            var documents = subjectOpt.get().subject().documents();
            if (documents.isEmpty() || documents.get().isEmpty()) return null;
            GetJourneyStateDocument document = documents.get().get(0);
            GetJourneyStateClassification classification = document.classification().orElse(null);
            if (classification == null) return null;
            String country = classification.countryName().orElse(null);
            String code = classification.countryCode().orElse(null);
            String type = classification.type().orElse(null);
            String year = classification.year().orElse(null);
            StringBuilder label = new StringBuilder();
            if (country != null) {
                label.append(country);
                if (code != null) label.append(" (").append(code).append(")");
            }
            if (type != null) {
                if (!label.isEmpty()) label.append(" ");
                label.append(type);
            }
            if (year != null) {
                label.append(" (").append(year).append(")");
            }
            return label.isEmpty() ? null : label.toString();
        } catch (RuntimeException e) {
            // Defensive: this path is unverified against a live response, so
            // any unexpected shape falls back to the raw `data` walk instead
            // of breaking the whole record screen.
            log.debug("Typed document classification read failed, falling back to raw data map", e);
            return null;
        }
    }

    private static String rawDocumentTypeLabel(Map<String, Object> data) {
        List<Object> steps = asList(data.get("steps"));
        if (steps == null) {
            Map<String, Object> context = asMap(data.get("context"));
            Map<String, Object> process = context == null ? null : asMap(context.get("process"));
            steps = process == null ? null : asList(process.get("steps"));
        }
        if (steps == null) return null;
        for (Object entry : steps) {
            Map<String, Object> step = asMap(entry);
            if (step == null) continue;
            Map<String, Object> result = asMap(step.get("result"));
            if (result == null) continue;
            Map<String, Object> subject = asMap(result.get("subject"));
            if (subject == null) continue;
            if (!(subject.get("documents") instanceof List<?> documents) || documents.isEmpty()) continue;
            if (!(documents.get(0) instanceof Map<?, ?> document)) continue;
            if (!(document.get("classification") instanceof Map<?, ?> classification)) continue;
            Object name = classification.get("name");
            if (name != null && !String.valueOf(name).isBlank()) {
                return String.valueOf(name);
            }
        }
        return null;
    }

    private String firstModuleErrorAction(List<RawStep> steps) {
        return steps.stream()
                .map(RawStep::errorFirstAction)
                .filter(a -> a != null && !a.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static final java.time.format.DateTimeFormatter TIMESTAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss", Locale.ENGLISH)
                    .withZone(java.time.ZoneOffset.UTC);

    private static String formatTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return TIMESTAMP_FORMAT.format(java.time.Instant.parse(iso));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String formatDurationSeconds(String startIso, String endIso) {
        if (startIso == null || endIso == null) return null;
        try {
            java.time.Duration elapsed = java.time.Duration.between(
                    java.time.Instant.parse(startIso), java.time.Instant.parse(endIso));
            return String.format(Locale.ROOT, "%.1f seconds", elapsed.toMillis() / 1000.0);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String formatModuleMs(Long durationMilliSec) {
        if (durationMilliSec == null) return null;
        return String.format(Locale.ROOT, "%.1fs", durationMilliSec / 1000.0);
    }

    private static String latestStepEndedAt(List<RawStep> steps) {
        String latest = null;
        for (RawStep step : steps) {
            String ended = step.endedAt();
            if (ended != null && (latest == null || ended.compareTo(latest) > 0)) {
                latest = ended;
            }
        }
        return latest;
    }

    private Decision mapDecision(RawResult result) {
        String classification = result == null ? null : result.outcomeClassification();
        if (classification == null) return Decision.REFER;
        return switch (classification.toLowerCase(Locale.ROOT)) {
            case "positive" -> Decision.PASS;
            case "negative" -> Decision.FAIL;
            default -> Decision.REFER;
        };
    }

    private ModuleState mapModuleState(RawStep step) {
        if (step.status() != null) {
            return switch (step.status().toLowerCase(Locale.ROOT)) {
                case "error", "timeout" -> ModuleState.FAIL;
                case "pending" -> ModuleState.RUNNING;
                case "complete" -> classify(step.outcomeClassification(), outcomeState(step.outcome()));
                default -> ModuleState.REVIEW;
            };
        }
        return classify(step.outcomeClassification(), ModuleState.RUNNING);
    }

    private static ModuleState outcomeState(String outcome) {
        if (outcome != null && POSITIVE_OUTCOMES.contains(outcome.toLowerCase(Locale.ROOT))) {
            return ModuleState.PASS;
        }
        return ModuleState.REVIEW;
    }

    private ModuleState classify(String outcomeClassification, ModuleState whenUnclassified) {
        if (outcomeClassification == null) return whenUnclassified;
        return switch (outcomeClassification.toLowerCase(Locale.ROOT)) {
            case "positive" -> ModuleState.PASS;
            case "negative" -> ModuleState.FAIL;
            default -> ModuleState.REVIEW;
        };
    }

    // ------------------------------------------------------------------
    // interactions().submit() request building — replaces the raw
    // GoInteractionSubmitRequest.of() factory, restated against typed SDK
    // subject/document/biometric/consent models instead of a raw nested map.
    // ------------------------------------------------------------------

    private static final List<String> CONSENT_KEYS =
            List.of("shareWithClinicians", "sharePrescriptions", "useForResearch");

    /**
     * Maps the front end's flat, field-name-keyed submission onto the SDK's
     * typed submit request — structurally the same table-driven approach as
     * {@code GoInteractionSubmitRequest.of()}, adapted so each entry builds a
     * piece of a typed {@code SubmitInteractionIdentity}/
     * {@code SubmitInteractionDocument}/{@code SubmitInteractionBiometricUnion}
     * instead of writing into a raw nested map. Same caveat as the original:
     * deliberately narrow, a sample mapping invented before any journey
     * existed rather than read from a published journey's schema — a real
     * deployment replaces this with the journey's own schema
     * (Dashboard → journey → Actions → View schema).
     *
     * <b>Unlike the raw version, an unmapped key has nowhere generic to go</b>:
     * {@code GoInteractionSubmitRequest} could always fall back to a flat
     * {@code subject.<key>} placement in its untyped map, but
     * {@code SubmitInteractionRequest} carries no untyped catch-all field at
     * all (confirmed by reading its source — only {@code instanceId}/
     * {@code interactionId}/{@code participants}/{@code context}). An
     * unrecognised key is logged and dropped rather than guessed at; see this
     * module's README, "Known gaps".
     */
    public SubmitInteractionRequest toSubmitRequest(String instanceId, String interactionId,
                                                      Map<String, Object> data, String consentUrl) {
        List<Participant> participants = new ArrayList<>();
        if (data == null || data.isEmpty()) {
            return new SubmitInteractionRequest(instanceId, interactionId, List.of(),
                    new SubmitInteractionContext(new SubmitInteractionSubject()));
        }

        IdentityAccumulator identity = new IdentityAccumulator();
        List<SubmitInteractionDocument> documents = new ArrayList<>();
        List<SubmitInteractionBiometricUnion> biometrics = new ArrayList<>();
        List<SubmitInteractionConsent> consent = new ArrayList<>();

        if (data.keySet().stream().anyMatch(CONSENT_KEYS::contains)) {
            participants.add(new Participant("Consent", null));
            consent.add(consentRecord(data, consentUrl));
        }

        data.forEach((key, value) -> {
            if (CONSENT_KEYS.contains(key)) return;
            FieldMapping mapping = FieldMapping.forKey(key);
            participants.add(new Participant(mapping.domainElementId, null));
            mapping.apply(identity, documents, biometrics, value);
        });

        SubmitInteractionSubject.Builder subjectBuilder = SubmitInteractionSubject.builder();
        if (identity.hasAnyValue()) {
            subjectBuilder.identity(identity.build());
        }
        if (!documents.isEmpty()) {
            subjectBuilder.documents(documents);
        }
        if (!biometrics.isEmpty()) {
            subjectBuilder.biometrics(biometrics);
        }
        if (!consent.isEmpty()) {
            subjectBuilder.consent(consent);
        }

        return new SubmitInteractionRequest(instanceId, interactionId, participants,
                new SubmitInteractionContext(subjectBuilder.build()));
    }

    private static SubmitInteractionConsent consentRecord(Map<String, Object> data, String consentUrl) {
        String purpose = CONSENT_KEYS.stream()
                .filter(k -> Boolean.TRUE.equals(data.get(k)))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return SubmitInteractionConsent.builder()
                .url(consentUrl)
                .terms("I agree that Meridian Health may access and share my patient record "
                        + "with clinicians treating me.")
                .effectiveDate(java.time.Instant.now().toString())
                .purpose(purpose)
                .build();
    }

    /** Mutable accumulator for the identity fields spread across one submit's field-by-field entries. */
    private static final class IdentityAccumulator {
        String firstName;
        String lastNames;
        String dateOfBirth;
        String gender;
        String mothersMaidenName;
        final Map<String, String> addressComponents = new LinkedHashMap<>();
        final List<SubmitInteractionIdentityPhone> phones = new ArrayList<>();
        final List<SubmitInteractionIdentityEmail> emails = new ArrayList<>();
        final List<SubmitInteractionIdentityIdNumber> idNumbers = new ArrayList<>();
        final List<SubmitInteractionIdentityPreviousAddress> previousAddresses = new ArrayList<>();

        boolean hasAnyValue() {
            return firstName != null || lastNames != null || dateOfBirth != null || gender != null
                    || mothersMaidenName != null || !addressComponents.isEmpty() || !phones.isEmpty()
                    || !emails.isEmpty() || !idNumbers.isEmpty() || !previousAddresses.isEmpty();
        }

        SubmitInteractionIdentity build() {
            SubmitInteractionIdentity.Builder b = SubmitInteractionIdentity.builder();
            if (firstName != null) b.firstName(firstName);
            if (lastNames != null) b.lastNames(List.of(lastNames));
            if (dateOfBirth != null) b.dateOfBirth(dateOfBirth);
            if (gender != null) b.gender(gender);
            if (mothersMaidenName != null) b.mothersMaidenName(mothersMaidenName);
            if (!addressComponents.isEmpty()) {
                SubmitInteractionIdentityCurrentAddress.Builder addr = SubmitInteractionIdentityCurrentAddress.builder();
                addressComponents.forEach((component, val) -> {
                    switch (component) {
                        case "premise" -> addr.premise(val);
                        case "subBuilding" -> addr.subBuilding(val);
                        case "building" -> addr.building(val);
                        case "thoroughfare" -> addr.thoroughfare(val);
                        case "dependentThoroughfare" -> addr.dependentThoroughfare(val);
                        case "locality" -> addr.locality(val);
                        case "dependentLocality" -> addr.dependentLocality(val);
                        case "postalCode" -> addr.postalCode(val);
                        case "country" -> addr.country(countryCode(val));
                        case "addressString" -> addr.addressString(val);
                        default -> log.debug("Unmapped address component {} — dropped", component);
                    }
                });
                b.currentAddress(addr.build());
            }
            if (!phones.isEmpty()) b.phones(phones);
            if (!emails.isEmpty()) b.emails(emails);
            if (!idNumbers.isEmpty()) b.idNumbers(idNumbers);
            if (!previousAddresses.isEmpty()) b.previousAddresses(previousAddresses);
            return b.build();
        }
    }

    private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
            Map.entry("UNITED KINGDOM", "GBR"),
            Map.entry("GREAT BRITAIN", "GBR"),
            Map.entry("ENGLAND", "GBR"),
            Map.entry("SCOTLAND", "GBR"),
            Map.entry("WALES", "GBR"),
            Map.entry("NORTHERN IRELAND", "GBR"),
            Map.entry("UK", "GBR"),
            Map.entry("IRELAND", "IRL"),
            Map.entry("UNITED STATES", "USA"),
            Map.entry("UNITED STATES OF AMERICA", "USA"),
            Map.entry("USA", "USA")
    );

    private static String countryCode(String typed) {
        String trimmed = typed == null ? "" : typed.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.matches("^[A-Z]{2,3}$")) {
            return upper;
        }
        String mapped = COUNTRY_CODES.get(upper);
        return mapped != null ? mapped : trimmed;
    }

    /** One front-end field name -> where it lands in the typed submit request. */
    private record FieldMapping(String domainElementId, Target target, String addressComponent) {

        private enum Target {
            FIRST_NAME, LAST_NAMES, DATE_OF_BIRTH, GENDER, MOTHERS_MAIDEN_NAME,
            ADDRESS_COMPONENT, PHONE_MOBILE, PHONE_LANDLINE, EMAIL_PERSONAL, EMAIL_WORK,
            ID_NUMBER_NI, ID_NUMBER_SSN, PREVIOUS_ADDRESS,
            DOCUMENT_SIDE1, DOCUMENT_SIDE2, SELFIE, DROPPED
        }

        private static FieldMapping of(String domainElementId, Target target) {
            return new FieldMapping(domainElementId, target, null);
        }

        private static FieldMapping address(String component) {
            return new FieldMapping("CurrentAddress", Target.ADDRESS_COMPONENT, component);
        }

        private static final Map<String, FieldMapping> KNOWN = buildKnown();

        private static Map<String, FieldMapping> buildKnown() {
            Map<String, FieldMapping> m = new LinkedHashMap<>();
            m.put("fullName", of("FullName", Target.FIRST_NAME));
            m.put("firstName", of("FullName", Target.FIRST_NAME));
            m.put("lastNames", of("FullName", Target.LAST_NAMES));
            m.put("dateOfBirth", of("DateOfBirth", Target.DATE_OF_BIRTH));

            m.put("premise", address("premise"));
            m.put("subBuilding", address("subBuilding"));
            m.put("dependentThoroughfare", address("dependentThoroughfare"));
            m.put("dependentLocality", address("dependentLocality"));
            m.put("addressString", address("addressString"));
            m.put("building", address("building"));
            m.put("thoroughfare", address("thoroughfare"));
            m.put("locality", address("locality"));
            m.put("postcode", address("postalCode"));
            m.put("postalCode", address("postalCode"));
            m.put("country", address("country"));

            m.put("mobileNumber", of("MobilePhone", Target.PHONE_MOBILE));
            m.put("MobilePhone/number", of("MobilePhone", Target.PHONE_MOBILE));
            m.put("LandlinePhone/number", of("LandlinePhone", Target.PHONE_LANDLINE));
            m.put("PersonalEmail/email", of("PersonalEmail", Target.EMAIL_PERSONAL));
            m.put("WorkEmail/email", of("WorkEmail", Target.EMAIL_WORK));
            m.put("MothersMaidenName", of("MothersMaidenName", Target.MOTHERS_MAIDEN_NAME));
            m.put("SSN", of("SSN", Target.ID_NUMBER_SSN));
            m.put("PreviousAddresses", of("PreviousAddresses", Target.PREVIOUS_ADDRESS));
            m.put("Gender", of("Gender", Target.GENDER));
            m.put("NationalInsuranceNumber", of("NationalInsuranceNumber", Target.ID_NUMBER_NI));

            m.put("documentImage", of("PrimaryDocument", Target.DOCUMENT_SIDE1));
            m.put("documentBack", of("PrimaryDocument", Target.DOCUMENT_SIDE2));
            m.put("selfieImage", of("Selfie", Target.SELFIE));
            return m;
        }

        static FieldMapping forKey(String key) {
            FieldMapping known = KNOWN.get(key);
            if (known == null && key.contains("/")) {
                known = KNOWN.get(key.substring(key.lastIndexOf('/') + 1));
            }
            if (known != null) return known;
            log.warn("No SDK submit mapping for field '{}' — dropped (SubmitInteractionRequest has no untyped "
                    + "escape hatch; see SdkInteractionMapper#toSubmitRequest javadoc)", key);
            return new FieldMapping(key, Target.DROPPED, null);
        }

        void apply(IdentityAccumulator identity, List<SubmitInteractionDocument> documents,
                   List<SubmitInteractionBiometricUnion> biometrics, Object value) {
            String text = String.valueOf(value);
            switch (target) {
                case FIRST_NAME -> identity.firstName = text;
                case LAST_NAMES -> identity.lastNames = text;
                case DATE_OF_BIRTH -> identity.dateOfBirth = text;
                case GENDER -> identity.gender = text;
                case MOTHERS_MAIDEN_NAME -> identity.mothersMaidenName = text;
                case ADDRESS_COMPONENT -> identity.addressComponents.put(addressComponent, text);
                case PHONE_MOBILE -> identity.phones.add(new SubmitInteractionIdentityPhone("mobile", text));
                case PHONE_LANDLINE -> identity.phones.add(new SubmitInteractionIdentityPhone("landline", text));
                case EMAIL_PERSONAL -> identity.emails.add(new SubmitInteractionIdentityEmail("personal", text));
                case EMAIL_WORK -> identity.emails.add(new SubmitInteractionIdentityEmail("work", text));
                case ID_NUMBER_NI -> identity.idNumbers.add(
                        new SubmitInteractionIdentityIdNumber("NationalInsuranceNumber", text, null));
                case ID_NUMBER_SSN -> identity.idNumbers.add(
                        new SubmitInteractionIdentityIdNumber("SSN", text, null));
                case PREVIOUS_ADDRESS -> identity.previousAddresses.add(
                        SubmitInteractionIdentityPreviousAddress.builder().addressString(text).build());
                case DOCUMENT_SIDE1 -> mergeDocument(documents, true, text);
                case DOCUMENT_SIDE2 -> mergeDocument(documents, false, text);
                case SELFIE -> biometrics.add(SubmitInteractionBiometricUnion.of(
                        // SubmitInteractionBiometric1's face1Image/face2Image both being @Nonnull
                        // rules it out for a flow that only ever captures one selfie image —
                        // Biometric4's single, required `selfieImage` field is the structural
                        // match (same field name as the old raw-HTTP shape, no second image this
                        // client doesn't have). Confirmed live 2026-09-18 (Meridian Health,
                        // public platform): the submit call accepted this shape without error and
                        // the journey progressed to a real decision — a schema mismatch here would
                        // have surfaced as an immediate 400 on the submit itself, not later.
                        new SubmitInteractionBiometric4(text)));
                case DROPPED -> { /* logged in forKey() */ }
            }
        }
    }

    private static void mergeDocument(List<SubmitInteractionDocument> documents, boolean side1, String image) {
        SubmitInteractionDocument existing = documents.isEmpty() ? null : documents.get(0);
        SubmitInteractionDocument.Builder builder = existing == null
                ? SubmitInteractionDocument.builder().type("Primary")
                : toBuilder(existing);
        if (side1) {
            builder.side1Image(image);
        } else {
            builder.side2Image(image);
        }
        SubmitInteractionDocument merged = builder.build();
        if (documents.isEmpty()) {
            documents.add(merged);
        } else {
            documents.set(0, merged);
        }
    }

    private static SubmitInteractionDocument.Builder toBuilder(SubmitInteractionDocument existing) {
        SubmitInteractionDocument.Builder b = SubmitInteractionDocument.builder().type("Primary");
        existing.side1Image().ifPresent(b::side1Image);
        existing.side2Image().ifPresent(b::side2Image);
        return b;
    }
}
