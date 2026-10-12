package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.InteractionFetchResponse;
import com.gbg.gocore.models.StateFetchResponse;
import com.gbg.gocore.models.operations.Config;
import com.gbg.gocore.models.operations.Participant;
import com.gbg.gocore.models.operations.StartJourneyContext;
import com.gbg.gocore.models.operations.StartJourneyRequest;
import com.gbg.gocore.models.operations.StartJourneySubject;
import com.gbg.gocore.utils.JSON;
import com.gbg.gocore.models.operations.SubmitInteractionBiometric4;
import com.gbg.gocore.models.operations.SubmitInteractionBiometricUnion;
import com.gbg.gocore.models.operations.SubmitInteractionConsent;
import com.gbg.gocore.models.operations.SubmitInteractionConsentType;
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
 * <h2>Response shapes in go-core-sdk 0.2.0</h2>
 * <ul>
 *   <li>{@code interactions().fetch()} returns {@code InteractionFetchResponse},
 *       whose {@code interaction} is an untyped map — {@code collects} is
 *       read from it the same way api-implementation's
 *       {@code GoInteractionFetchResponse} reads it ({@code ref}/{@code spec}),
 *       and {@code outstanding}/{@code instructions} arrive as untyped values
 *       read as string lists.</li>
 *   <li>{@code journeys().getState()} returns {@code StateFetchResponse}, whose
 *       {@code context} and {@code result} are untyped maps. Go sends the
 *       per-module steps under {@code context.process.steps} and the journey
 *       summary under {@code context.process.journey}; {@link #toRecord} reads
 *       both from there and the decision from {@code result}. In 0.1.0 the
 *       typed {@code context} had no {@code process} and the body had no
 *       {@code result}, so all of this was dropped and had to be recovered
 *       from the raw response bytes — no longer necessary.</li>
 *   <li>Document classification has no typed field in 0.2.0, so
 *       {@link #documentTypeLabel} reads {@code classification.name} from the
 *       same untyped map.</li>
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
     * Builds the SDK's {@code StartJourneyRequest} with {@code config.delivery=api}
     * and the prefill as {@code context.subject} — the same place the raw-HTTP
     * client puts it.
     *
     * The prefill is subject-shaped ({@code identity}, {@code documents}, ...),
     * so it is converted into the SDK's typed {@code StartJourneySubject}.
     * That type has no catch-all for extra properties and the SDK's mapper
     * ignores unknown ones, so any key it has no field for is logged rather
     * than dropped silently; a prefill that does not fit the type at all is
     * logged and the journey starts without it.
     */
    public StartJourneyRequest toStartRequest(String resourceId, Map<String, Object> prefill) {
        return new StartJourneyRequest(resourceId, new StartJourneyContext(toSubject(prefill), new Config("api")));
    }

    private static StartJourneySubject toSubject(Map<String, Object> prefill) {
        if (prefill == null || prefill.isEmpty()) return null;
        StartJourneySubject subject;
        try {
            subject = JSON.getMapper().convertValue(prefill, StartJourneySubject.class);
        } catch (IllegalArgumentException e) {
            log.warn("Journey prefill does not fit the SDK's StartJourneySubject; starting without it (keys {})",
                    prefill.keySet(), e);
            return null;
        }
        Map<?, ?> sent = JSON.getMapper().convertValue(subject, Map.class);
        Set<String> dropped = new java.util.LinkedHashSet<>(prefill.keySet());
        dropped.removeIf(key -> sent.get(key) != null);
        if (!dropped.isEmpty()) {
            log.warn("StartJourneySubject has no field for prefill keys {}; they are not sent", dropped);
        }
        return subject;
    }

    // ------------------------------------------------------------------
    // interactions().fetch() -> Interaction
    // ------------------------------------------------------------------

    /**
     * The next screen, for a journey with no stages submitted yet. Equivalent
     * to {@code toInteraction(body, Set.of())} — see the two-argument form's
     * javadoc for why a journey in flight must use it instead.
     */
    public Interaction toInteraction(InteractionFetchResponse body) {
        return toInteraction(body, Set.of());
    }

    /**
     * The next screen for an interaction fetch.
     *
     * A response with no {@code interaction} is Go still working with nothing
     * to show yet (0.1.0 modelled that as a separate {@code ResponseBody2}
     * shape): it gets the processing screen, whatever the status says, and
     * the front end's {@code /state} poll surfaces the decision.
     *
     * @param completed stage names already submitted on this journey; never null.
     */
    public Interaction toInteraction(InteractionFetchResponse body, Set<String> completed) {
        String goStatus = body.journey().status();
        String interactionId = body.interactionId().orElse(body.instanceId());

        if (body.interaction().isEmpty()) {
            return processingInteraction(interactionId);
        }
        if (isFailed(goStatus)) {
            return failedInteraction(interactionId);
        }

        List<String> outstandingRaw = stringList(body.outstanding().orElse(null));
        if (awaitingManualReview(outstandingRaw)) {
            return manualReviewInteraction(interactionId);
        }

        // An interaction body means there is something to collect or the
        // journey is pending customer input, so processing=false: mapStatus
        // falls through "InProgress" to PENDING_INPUT and the code below
        // picks a screen from `outstanding`/`collects`.
        JourneyStatus status = mapStatus(goStatus, false);
        if (status == JourneyStatus.COMPLETED) {
            // Defer to a processing screen and let the front end's /state poll
            // surface the real decision (journeys().getState()), as before.
            return processingInteraction(interactionId);
        }
        // Paused (e.g. awaiting a manual review): Go won't accept input, so
        // picking a screen from `outstanding` would show a form it rejects.
        // Same rule as DefaultInteractionMapper.
        if (status == JourneyStatus.IN_PROGRESS) {
            return processingInteraction(interactionId);
        }

        List<String> collectable = collectableRefs(body);
        List<String> outstanding = collectable.isEmpty() ? outstandingRaw : collectable;
        Set<String> requiredRefs = requiredRefs(body);
        List<String> instructions = stringList(body.instructions().orElse(null));

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

    // ------------------------------------------------------------------
    // Untyped interaction-fetch helpers. 0.2.0 types `interaction` as a Map
    // and `instructions`/`outstanding` as Object, so these read them the same
    // way api-implementation's GoInteractionFetchResponse reads raw JSON.
    // ------------------------------------------------------------------

    /** The string entries of a JSON array; empty for null or any other shape. Non-string entries are skipped. */
    public static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /** {@code interaction.collects[]} entries whose {@code ref} is a string. */
    private static List<Map<String, Object>> collects(InteractionFetchResponse body) {
        Map<String, Object> interaction = body.interaction().orElse(null);
        if (interaction == null || !(interaction.get("collects") instanceof List<?> entries)) return List.of();
        List<Map<String, Object>> collects = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            Map<String, Object> map = asMap(entry);
            if (map != null && map.get("ref") instanceof String) collects.add(map);
        }
        return collects;
    }

    /** Public so {@link GoSdkClient} can populate its collects-preferred cache from the same source. */
    public static List<String> collectableRefs(InteractionFetchResponse body) {
        return collects(body).stream()
                .map(collect -> (String) collect.get("ref"))
                .toList();
    }

    private static Set<String> requiredRefs(InteractionFetchResponse body) {
        return collects(body).stream()
                .filter(collect -> "required".equalsIgnoreCase(asString(collect.get("spec"))))
                .map(collect -> (String) collect.get("ref"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------
    // journeys().getState() -> RecordResponse
    // ------------------------------------------------------------------

    /**
     * @param state the full-view {@code journey/state/fetch} response; its
     *              {@code context} and {@code result} are untyped maps, read
     *              defensively below.
     */
    public RecordResponse toRecord(StateFetchResponse state) {
        String goStatus = state.status();
        Map<String, Object> data = stateData(state);

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
        if (state.instanceId() != null && !state.instanceId().isBlank()) {
            summary.add(new SummaryRow("Reference", state.instanceId()));
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
        String documentLabel = documentTypeLabel(data);
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

    /** Terminal check for {@code GoSdkClient.fetchState}: has Go reached a decision yet? */
    public boolean carriesRealResult(StateFetchResponse state) {
        RawResult result = rawResult(stateData(state));
        return result != null && result.outcomeClassification() != null;
    }

    // ------------------------------------------------------------------
    // Defensive map walking. 0.2.0 types the full state view's `context`
    // and `result` as untyped maps, so they are read the same way the
    // raw-HTTP client deserializes its own JSON.
    // ------------------------------------------------------------------

    /** The state response's untyped {@code context} and {@code result}, keyed as on the wire. */
    private static Map<String, Object> stateData(StateFetchResponse state) {
        Map<String, Object> data = new java.util.HashMap<>();
        state.context().ifPresent(context -> data.put("context", context));
        state.result().ifPresent(result -> data.put("result", result));
        return data;
    }

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

    /** Root-level `result`, mirroring the raw GoStateResponse.result() field. */
    private static RawResult rawResult(Map<String, Object> data) {
        Map<String, Object> result = asMap(data.get("result"));
        if (result == null) return null;
        return new RawResult(asString(result.get("outcome")), asString(result.get("outcomeClassification")));
    }

    /** {@code context.process}, where Go sends the per-module steps and journey summary. */
    private static Map<String, Object> process(Map<String, Object> data) {
        Map<String, Object> context = asMap(data.get("context"));
        return context == null ? null : asMap(context.get("process"));
    }

    /** {@code context.process.steps}. */
    private static List<RawStep> rawSteps(Map<String, Object> data) {
        Map<String, Object> process = process(data);
        List<Object> raw = process == null ? null : asList(process.get("steps"));
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
            Map<String, Object> stepProcess = asMap(step.get("process"));
            Map<String, Object> detail = stepProcess == null ? null : asMap(stepProcess.get("step"));
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

    /** {@code context.process.journey}. */
    private static RawJourney rawJourneyInfo(Map<String, Object> data) {
        Map<String, Object> process = process(data);
        Map<String, Object> journey = process == null ? null : asMap(process.get("journey"));
        if (journey == null) return null;
        return new RawJourney(asString(journey.get("name")), asString(journey.get("version")),
                asString(journey.get("startedAt")), asString(journey.get("endedAt")));
    }

    /**
     * The document type Document Classification reported: the first step
     * whose {@code result.subject.documents[0].classification.name} is set,
     * read the way api-implementation's mapper reads it.
     */
    private static String documentTypeLabel(Map<String, Object> data) {
        Map<String, Object> process = process(data);
        List<Object> steps = process == null ? null : asList(process.get("steps"));
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
            // A null would otherwise go out as the string "null" (e.g.
            // email:"null" → Go 400), and a dropped key still declared as a
            // participant makes Go expect data it never receives.
            if (CONSENT_KEYS.contains(key) || value == null) return;
            FieldMapping mapping = FieldMapping.forKey(key);
            if (mapping.target() == FieldMapping.Target.DROPPED) return;
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

    /**
     * Go path → submitted field name, for the same {@code data}
     * {@link #toSubmitRequest} would send — so a 400/422 whose problems name
     * paths ({@code context.subject.identity.emails.0.email: Invalid email address})
     * can be mapped back to the fields the customer typed into. Array indices
     * depend on placement order, so this runs the same placement.
     */
    public Map<String, String> fieldsByGoPath(Map<String, Object> data) {
        Map<String, String> byPath = new LinkedHashMap<>();
        if (data == null) {
            return byPath;
        }
        IdentityAccumulator identity = new IdentityAccumulator();
        List<SubmitInteractionDocument> documents = new ArrayList<>();
        List<SubmitInteractionBiometricUnion> biometrics = new ArrayList<>();
        data.forEach((key, value) -> {
            if (CONSENT_KEYS.contains(key) || value == null) return;
            String path = FieldMapping.forKey(key).apply(identity, documents, biometrics, value);
            if (path != null) {
                byPath.put(path, key);
            }
        });
        return byPath;
    }

    private static SubmitInteractionConsent consentRecord(Map<String, Object> data, String consentUrl) {
        String purpose = CONSENT_KEYS.stream()
                .filter(k -> Boolean.TRUE.equals(data.get(k)))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return SubmitInteractionConsent.builder()
                .type(SubmitInteractionConsentType.EXPLICIT)
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

        /**
         * Places {@code value} and returns the path Go would name it by in a
         * validation error (e.g. {@code context.subject.identity.emails.0.email}),
         * or null for a dropped key.
         */
        String apply(IdentityAccumulator identity, List<SubmitInteractionDocument> documents,
                     List<SubmitInteractionBiometricUnion> biometrics, Object value) {
            String text = String.valueOf(value);
            String id = "context.subject.identity.";
            switch (target) {
                case FIRST_NAME -> { identity.firstName = text; return id + "firstName"; }
                case LAST_NAMES -> { identity.lastNames = text; return id + "lastNames.0"; }
                case DATE_OF_BIRTH -> { identity.dateOfBirth = text; return id + "dateOfBirth"; }
                case GENDER -> { identity.gender = text; return id + "gender"; }
                case MOTHERS_MAIDEN_NAME -> { identity.mothersMaidenName = text; return id + "mothersMaidenName"; }
                case ADDRESS_COMPONENT -> {
                    identity.addressComponents.put(addressComponent, text);
                    return id + "currentAddress." + addressComponent;
                }
                case PHONE_MOBILE -> {
                    identity.phones.add(new SubmitInteractionIdentityPhone("mobile", text));
                    return id + "phones." + (identity.phones.size() - 1) + ".number";
                }
                case PHONE_LANDLINE -> {
                    identity.phones.add(new SubmitInteractionIdentityPhone("landline", text));
                    return id + "phones." + (identity.phones.size() - 1) + ".number";
                }
                case EMAIL_PERSONAL -> {
                    identity.emails.add(new SubmitInteractionIdentityEmail("personal", text));
                    return id + "emails." + (identity.emails.size() - 1) + ".email";
                }
                case EMAIL_WORK -> {
                    identity.emails.add(new SubmitInteractionIdentityEmail("work", text));
                    return id + "emails." + (identity.emails.size() - 1) + ".email";
                }
                case ID_NUMBER_NI -> {
                    identity.idNumbers.add(new SubmitInteractionIdentityIdNumber("NationalInsuranceNumber", text, null));
                    return id + "idNumbers." + (identity.idNumbers.size() - 1) + ".idNumber";
                }
                case ID_NUMBER_SSN -> {
                    identity.idNumbers.add(new SubmitInteractionIdentityIdNumber("SSN", text, null));
                    return id + "idNumbers." + (identity.idNumbers.size() - 1) + ".idNumber";
                }
                case PREVIOUS_ADDRESS -> {
                    identity.previousAddresses.add(
                            SubmitInteractionIdentityPreviousAddress.builder().addressString(text).build());
                    return id + "previousAddresses." + (identity.previousAddresses.size() - 1) + ".addressString";
                }
                case DOCUMENT_SIDE1 -> { mergeDocument(documents, true, text); return "context.subject.documents.0.side1Image"; }
                case DOCUMENT_SIDE2 -> { mergeDocument(documents, false, text); return "context.subject.documents.0.side2Image"; }
                case SELFIE -> { biometrics.add(SubmitInteractionBiometricUnion.of(
                        // SubmitInteractionBiometric1's face1Image/face2Image both being @Nonnull
                        // rules it out for a flow that only ever captures one selfie image —
                        // Biometric4's single, required `selfieImage` field is the structural
                        // match (same field name as the old raw-HTTP shape, no second image this
                        // client doesn't have). Confirmed live 2026-09-18 (Meridian Health,
                        // public platform): the submit call accepted this shape without error and
                        // the journey progressed to a real decision — a schema mismatch here would
                        // have surfaced as an immediate 400 on the submit itself, not later.
                        // type "Selfie" as well, matching the raw-HTTP shape: Liveness
                        // Verification's reference lists it as required alongside the image.
                        new SubmitInteractionBiometric4(null, "Selfie", text, null)));
                    return "context.subject.biometrics." + (biometrics.size() - 1) + ".selfieImage";
                }
                case DROPPED -> { return null; /* logged in forKey() */ }
            }
            return null;
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
