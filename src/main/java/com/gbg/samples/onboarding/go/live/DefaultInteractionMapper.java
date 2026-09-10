package com.gbg.samples.onboarding.go.live;

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
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import com.gbg.samples.onboarding.go.live.dto.GoInteractionFetchResponse;
import com.gbg.samples.onboarding.go.live.dto.GoStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns Go's raw, domain-element-shaped interaction into the front end's
 * opinionated {@code Interaction} DTO (screen kind, copy, field labels).
 *
 * Which domain elements become which screen, in what order and with what
 * copy, is {@link ScreenPlanProperties} — config, not code — so this class
 * itself has no knowledge of any one market's presentation. Go gives back
 * which domain elements are outstanding, not English copy or a screen kind;
 * turning that into screens is a product/copy decision per journey
 * (front-end handoff, section 6, "composition of the verification record"
 * makes the same point about the record endpoint), made once in a market's
 * {@code application-<market>.yml} rather than here.
 */
@Component
public class DefaultInteractionMapper {

    private static final Logger log = LoggerFactory.getLogger(DefaultInteractionMapper.class);

    /**
     * Human labels for domain elements common enough to be worth naming
     * outright; anything else falls back to a de-camel-cased leaf (see
     * {@link #label}). These are Go's own generic Data Verification element
     * names, not any one market's copy, so — unlike the screen plan — they
     * stay here rather than in per-market config.
     */
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
            Map.entry("Gender", "Gender")
    );

    private final ScreenPlanProperties screenPlan;

    public DefaultInteractionMapper(ScreenPlanProperties screenPlan) {
        this.screenPlan = screenPlan;
    }

    /**
     * The next screen, for a journey with no stages submitted yet.
     *
     * Equivalent to {@code toInteraction(response, Set.of())}. A journey in
     * flight must use the two-argument form: without the completed set, a
     * multi-stage plan re-picks its first stage on every fetch, because Go's
     * `outstanding` never shrinks.
     */
    public Interaction toInteraction(GoInteractionFetchResponse response) {
        return toInteraction(response, java.util.Set.of());
    }

    /**
     * The next screen, given the stages already submitted on this journey.
     *
     * @param completed stage names (as configured in {@code screen-plan.stages[].name})
     *                  the customer has already submitted; never null.
     */
    public Interaction toInteraction(GoInteractionFetchResponse response, java.util.Set<String> completed) {
        String goStatus = response.journey() == null ? null : response.journey().status();
        JourneyStatus status = mapStatus(goStatus, response.processing());
        String interactionId = response.interactionId() != null ? response.interactionId() : response.instanceId();

        // A Failed journey is unrecoverable: no further interaction will ever
        // arrive, so anything that isn't terminal leaves the customer waiting
        // on a screen that can never advance. The front end's JourneyStatus has
        // no Failed member (see onboarding-core types.ts — it is InProgress |
        // PendingInput | Completed), so this surfaces as what that contract can
        // represent: a terminal result screen carrying a `fail` decision.
        if (isFailed(goStatus)) {
            return new Interaction(
                    interactionId, ScreenKind.RESULT, "Decision", null,
                    "We could not complete your verification",
                    "Something went wrong while we were checking your details. No decision was reached.",
                    null, "Done", null, null, null, null, null, null, null, null,
                    Decision.FAIL, null, List.of(), null, null
            );
        }

        if (status == JourneyStatus.COMPLETED) {
            Decision decision = mapDecision(response.result());
            return new Interaction(
                    interactionId, ScreenKind.RESULT, "Decision", null,
                    decision == Decision.FAIL ? "We could not complete your verification" : "Verification complete",
                    response.result() == null ? null : response.result().outcome(),
                    null, "Done", null, null, null, null, null, null, null, null,
                    decision, null, List.of(), null, null
            );
        }

        if (status == JourneyStatus.IN_PROGRESS) {
            return processingInteraction(interactionId);
        }

        // What this interaction can collect, preferring `collects` over
        // `outstanding`.
        //
        // `outstanding` names only what Go is currently blocking on, so an
        // element whose parent is optional never appears in it. On the
        // Northbank journey that is 6 refs against the 37 in `collects` —
        // every PrimaryDocument/* field, both emails, both phones,
        // MothersMaidenName, Gender and NationalInsuranceNumber are missing
        // from it. Selecting screens on `outstanding` therefore silently drops
        // most of the journey's own pages: the document, personal-details and
        // contact-details screens never render, however the plan is written.
        //
        // `collects` is the interaction's full contract (fetch-interaction
        // reference), so it is what the screen plan matches against. Falls
        // back to `outstanding` when an interaction carries no collects — the
        // mock, and any market whose journey predates this field.
        List<String> collectable = response.collects().stream()
                .map(GoInteractionFetchResponse.Collect::ref)
                .toList();
        List<String> outstanding = collectable.isEmpty()
                ? (response.outstanding() == null ? List.of() : response.outstanding())
                : collectable;

        // The refs the journey marks required, so a form stage can render those
        // and leave the optional ones out. Empty when the interaction carries
        // no collects, which fieldsFor reads as "show everything named".
        java.util.Set<String> requiredRefs = response.collects().stream()
                .filter(GoInteractionFetchResponse.Collect::required)
                .map(GoInteractionFetchResponse.Collect::ref)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // Nothing to collect but not yet Completed: modules are running.
        if (outstanding.isEmpty()) {
            return processingInteraction(interactionId);
        }

        // Go returns a single interaction listing everything the journey
        // collects; splitting that into screens is the client's job under
        // `delivery: "api"`. The configured screen plan holds that decision —
        // the first stage in order that claims something and has not already
        // been submitted wins.
        //
        // `outstanding` is a static declaration, not a shrinking to-do list:
        // it comes back byte-identical after a successful submit (see
        // GoApiClient.completedStagesByInstance). Filtering on it alone would
        // re-pick stage one forever, so `completed` is what actually advances
        // the journey.
        // The document stage holds until Go has finished with the document.
        //
        // Document Classification reads side 1 and, for a two-sided type (a
        // driving licence, a residence permit), asks for the back. That answer
        // does not come back with the submit: for the first few seconds the
        // fetch still says LazySide2CollectionRequired — the pre-capture
        // state — and only then settles on Side2Required or Side2Done
        // (measured at about three seconds on the live tenant, 2026-09-10).
        //
        // Advancing during that window is what broke Facematch. The customer
        // reached the selfie screen and submitted it, then Side2Required
        // arrived, the back-of-document screen opened after the selfie, and
        // that later submit replaced subject.biometrics with the document's
        // own anchorImage — leaving Selfie/selfieImage outstanding and
        // Facematch with nothing to compare, so it never ran.
        //
        // So while the document is unresolved the stage stays put: showing
        // the back-of-document screen once Go asks for it, and the processing
        // screen while Go is still deciding. Both keep the selfie screen out
        // of reach until the document is genuinely finished.
        //
        // Read from Go's own `outstanding`, never the collects-substituted
        // list above: `collects` names PrimaryDocument/side2Image on every
        // fetch from the first, and testing that would open the back screen
        // before the front had been captured.
        java.util.Optional<ScreenPlanProperties.Stage> documentStage = screenPlan.stages().stream()
                .filter(stage -> stage.claimsRef("PrimaryDocument/side1Image"))
                .findFirst();
        boolean side1Submitted = documentStage.isPresent() && completed.contains(documentStage.get().name());

        if (documentStage.isPresent() && side1Submitted) {
            if (side2Required(response.outstanding(), response.instructions())) {
                return toStagedInteraction(interactionId, documentStage.get().asSecondSide(),
                        outstanding, completed, requiredRefs);
            }
            // Classification has not answered yet. Waiting is the only correct
            // move — the alternative is guessing, and guessing wrong costs the
            // customer their selfie.
            if (side2Undecided(response.instructions())) {
                return processingInteraction(interactionId);
            }
        }

        // Every configured stage submitted, while Go still lists the elements
        // they collect. That is the end of the collection phase on a journey
        // whose `outstanding` never shrinks — the modules are running, and the
        // decision arrives by polling getState. Without this the fall-through
        // below reads those still-listed elements as unclaimed and renders the
        // generic form, putting a raw field dump after the last real screen.
        boolean planConfigured = !screenPlan.stages().isEmpty();
        boolean everyStageSubmitted = planConfigured && screenPlan.stages().stream()
                .allMatch(stage -> completed.contains(stage.name()));
        if (everyStageSubmitted) {
            return processingInteraction(interactionId);
        }

        return screenPlan.stages().stream()
                .filter(stage -> !completed.contains(stage.name()))
                // Claimed, or configured to run regardless: a journey that
                // collects an element lazily never lists it, but still accepts
                // it (see Stage.alwaysCollects).
                .filter(stage -> stage.claims(outstanding) || stage.alwaysCollects())
                .findFirst()
                .map(stage -> toStagedInteraction(interactionId, stage, outstanding, completed, requiredRefs))
                // The plan doesn't recognise everything that's outstanding (e.g. a
                // module restored after the plan was written, or no plan configured
                // at all for this market yet). Falling back to Processing here — as
                // this used to, indiscriminately — would park the customer on a
                // screen that can never advance, since nothing will ever satisfy the
                // elements no stage claims. A plain form is at least usable, and the
                // warning below is what would tell you to update the plan.
                .orElseGet(() -> unmappedElementsInteraction(interactionId, outstanding));
    }

    private Interaction processingInteraction(String interactionId) {
        return new Interaction(
                interactionId, ScreenKind.PROCESSING, "Processing", null, "Running your checks",
                "This usually takes a few seconds.", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
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

    /** One screen from the plan, with a rail showing where the customer has got to. */
    private Interaction toStagedInteraction(String interactionId, ScreenPlanProperties.Stage stage,
                                            List<String> outstanding, java.util.Set<String> completed,
                                            java.util.Set<String> requiredRefs) {
        boolean reachedCurrent = false;
        List<StagePlanEntry> rail = new java.util.ArrayList<>();
        for (ScreenPlanProperties.Stage planStage : screenPlan.stages()) {
            StageState state;
            if (planStage.stage().equals(stage.stage())) {
                state = StageState.ACTIVE;
                reachedCurrent = true;
            } else if (reachedCurrent) {
                state = StageState.UPCOMING;
            } else {
                // Before the active stage. Done on either signal, because
                // journeys differ in which one arrives: this service recorded
                // the submit, or Go satisfied the elements and stopped listing
                // them (Meridian's `outstanding` shrinks; Northbank's, a static
                // declaration, never does).
                //
                // A stage the customer skipped past — still collectable, never
                // submitted — stays UPCOMING rather than DONE: the front end's
                // vocabulary is done | active | upcoming (onboarding-core
                // types.ts) with no "skipped", and UPCOMING is the honest half
                // of that choice. An always-collect stage is never "satisfied"
                // by absence either, since it was never listed to begin with.
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

    /**
     * Fields for a form stage, derived from what Go says is outstanding.
     * Unused while no configured plan has a FORM-kind stage, but kept so one
     * can be added (e.g. Meridian's DETAILS stage, once Data Verification is
     * restored — see application-meridian-health.yml) without rewriting this
     * class.
     */
    private List<FieldSchema> fieldsFor(ScreenPlanProperties.Stage stage, List<String> outstanding,
                                        java.util.Set<String> requiredRefs) {
        // Prefer the refs the journey marks required, but never render an empty
        // screen.
        //
        // `collects` carries every optional field alongside the required ones —
        // CurrentAddress lists 17, of which 5 are required — and rendering all
        // of them puts a dozen boxes (postBox, doubleDependentLocality,
        // superAdministrativeArea …) on a screen nobody is asked to fill in.
        // But a stage can legitimately claim only optional refs: the personal
        // details this journey collects (MothersMaidenName, Gender,
        // NationalInsuranceNumber) are all spec=optional, and filtering them
        // out leaves a form with a heading, a Continue button and nothing to
        // type into. So required-only when the stage has any, everything it
        // claims otherwise, and the field's own `required` flag carries the
        // distinction to the front end.
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

    /**
     * Input types for elements the front end can render more helpfully than a
     * plain text box. Its FieldSchema type vocabulary is text | date | tel |
     * email | postcode (onboarding-core types.ts) — there is no select, so a
     * coded field is a text box plus the guidance below.
     */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("CurrentAddress/postalCode", "postcode"),
            Map.entry("DateOfBirth", "date"),
            Map.entry("MobilePhone/number", "tel"),
            Map.entry("PersonalEmail/email", "email"),
            Map.entry("WorkEmail/email", "email")
    );

    /**
     * Guidance for fields Go validates against a format the label alone does
     * not convey. Country is the one that actually bites: Go requires
     * /^[A-Z]{2,3}$/, so "United Kingdom" is rejected with a 400 that reaches
     * the customer as a Continue button that does nothing.
     */
    private static final Map<String, String> HELPER_TEXT = Map.ofEntries(
            Map.entry("CurrentAddress/country", "Three-letter country code, e.g. GBR")
    );

    private static final Map<String, String> PLACEHOLDERS = Map.ofEntries(
            Map.entry("CurrentAddress/country", "GBR"),
            Map.entry("CurrentAddress/postalCode", "SW1A 2AA")
    );

    /**
     * Whether the capture screen now being submitted is the document one.
     *
     * TRUE for a document stage, FALSE for any other capture stage, and null
     * when no configured stage is current — the caller then falls back to
     * reading {@code outstanding} directly, which is all a market with no
     * screen plan has to go on.
     *
     * Selected by the same rule that rendered the screen, so the classification
     * and the screen cannot disagree. Reading {@code outstanding} for a
     * {@code PrimaryDocument/} entry instead is wrong on a journey that
     * collects the document lazily and so never lists it: every capture then
     * looks like a selfie, and the document image is submitted as
     * {@code subject.biometrics} where Document Classification never sees it.
     */
    public Boolean currentCaptureIsDocument(List<String> outstanding, java.util.Set<String> completed) {
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

    /**
     * The plan stage a submit answers, or null if none matches.
     *
     * Matched on the submitted field names: a FORM stage's fields are named
     * after the domain element refs it claims ({@code CurrentAddress/postalCode}),
     * so the stage prefix identifies them directly. Capture and consent screens
     * send short names instead ({@code selfieImage}, {@code documentImage}),
     * which carry no prefix — those fall back to the first not-yet-completed
     * stage whose capture type or kind fits, which is the stage the customer
     * was being shown.
     *
     * Returns null rather than guessing when nothing matches: the caller only
     * records progress for a real match, so an unrecognised submit leaves the
     * journey where it was instead of silently skipping a screen.
     */
    public String stageFor(java.util.Collection<String> submittedKeys, java.util.Set<String> completed) {
        if (submittedKeys == null || submittedKeys.isEmpty()) {
            return null;
        }
        List<ScreenPlanProperties.Stage> remaining = screenPlan.stages().stream()
                .filter(s -> !completed.contains(s.name()))
                .toList();

        // A prefixed field name identifies its stage outright.
        for (ScreenPlanProperties.Stage stage : remaining) {
            for (String key : submittedKeys) {
                if (stage.claimsRef(key)) {
                    return stage.name();
                }
            }
        }

        // Short names from a capture or consent screen: the element the stage
        // collects is in the prefix, so match its leaf against the key.
        for (ScreenPlanProperties.Stage stage : remaining) {
            String element = stage.prefix().endsWith("/")
                    ? stage.prefix().substring(0, stage.prefix().length() - 1)
                    : stage.prefix();
            for (String key : submittedKeys) {
                if (key == null) continue;
                boolean selfie = "Selfie".equals(element) && key.toLowerCase(Locale.ROOT).contains("selfie");
                boolean document = "PrimaryDocument".equals(element) && key.toLowerCase(Locale.ROOT).contains("document");
                boolean consent = "Consent".equals(element)
                        && (stage.kind() == ScreenKind.CONSENT || key.toLowerCase(Locale.ROOT).contains("consent"));
                if (selfie || document || consent) {
                    return stage.name();
                }
            }
        }

        // A consent screen submits its checkbox names, which match nothing
        // above. It is the only kind whose payload need not name its element,
        // so an unmatched submit belongs to the first outstanding consent stage.
        return remaining.stream()
                .filter(s -> s.kind() == ScreenKind.CONSENT)
                .map(ScreenPlanProperties.Stage::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether Go is waiting for the back of the document.
     *
     * Two signals, either of which is enough. Go names
     * {@code PrimaryDocument/side2Image} in {@code outstanding} once
     * Classification has read side 1 and found a two-sided document type, and
     * separately instructs {@code Side2Required}. The instruction is the
     * clearer of the two, but it travels alongside {@code Side2Done} and
     * {@code LazySide2CollectionRequired} on the same journey at different
     * points, so both are read rather than relying on either alone.
     *
     * {@code LazySide2CollectionRequired} deliberately does not count: it is
     * the pre-capture state, present from the very first fetch, and treating
     * it as a request for side 2 would show the back-of-document screen before
     * the front had been taken.
     */
    private static boolean side2Required(List<String> outstanding, List<String> instructions) {
        if (outstanding != null && outstanding.contains("PrimaryDocument/side2Image")) {
            return true;
        }
        return instructions != null && instructions.stream().anyMatch("Side2Required"::equalsIgnoreCase);
    }

    /**
     * Whether Go has yet to say whether it wants the back of the document.
     *
     * {@code LazySide2CollectionRequired} is the pre-decision state: it is
     * present from the very first fetch of a journey that *might* want a
     * second side, and stays there for the few seconds Document
     * Classification takes to read side 1. It is replaced by
     * {@code Side2Required} or {@code Side2Done} once that answer exists.
     *
     * Only meaningful once side 1 has been submitted — before that it means
     * "this journey can take a second side", not "an answer is pending" —
     * which is why the caller checks it inside that branch and not on its own.
     */
    private static boolean side2Undecided(List<String> instructions) {
        return instructions != null
                && instructions.stream().anyMatch("LazySide2CollectionRequired"::equalsIgnoreCase);
    }

    /** A human label for a domain element ref, falling back to a de-camel-cased leaf. */
    private static String label(String ref) {
        String known = LABELS.get(ref);
        if (known != null) return known;
        String leaf = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
        String spaced = leaf.replaceAll("(?<!^)(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public RecordResponse toRecord(GoStateResponse response) {
        // On a Failed journey the result object is often absent or incomplete;
        // mapDecision would default that to REFER ("With our team"), telling the
        // customer a review is under way when nothing is running at all.
        Decision decision = isFailed(response.status()) ? Decision.FAIL : mapDecision(response.result());
        List<ModuleRun> moduleRuns = response.allSteps().stream()
                .map(step -> new ModuleRun(
                        step.name() != null ? step.name() : step.nodeId(),
                        mapModuleState(step)))
                .toList();

        // A module that could not run is not a customer who failed a check.
        // Telling someone they were "Declined" when the platform errored is
        // both wrong and consequential — a decline invites an appeal, an error
        // invites another attempt — so the two are worded differently, and the
        // error case borrows Go's own advice ("Please verify the document image
        // and try again") over anything generic we could write.
        String moduleAdvice = firstModuleErrorAction(response);
        boolean systemError = decision == Decision.FAIL && moduleAdvice != null;

        String title = systemError
                ? "We could not run your checks"
                : switch (decision) {
                    case PASS -> "Verification complete";
                    case FAIL -> "We could not complete your verification";
                    case REFER -> "With our team";
                };

        String body = systemError
                ? moduleAdvice
                : switch (decision) {
                    case PASS -> "Your identity has been verified.";
                    case FAIL -> "We were not able to verify your identity from what you provided.";
                    case REFER -> "Someone is reviewing your details. We will be in touch.";
                };

        return new RecordResponse(
                decision,
                title,
                "",
                body,
                systemError ? "Try again" : "Done",
                moduleRuns,
                List.of(),
                null,
                systemError
        );
    }

    /**
     * The action Go suggests for the first module that errored, or null when
     * none did. Distinguishes "the platform broke" from "the customer did not
     * pass", which read identically in the response's own decision fields.
     */
    private String firstModuleErrorAction(GoStateResponse response) {
        return response.allSteps().stream()
                .map(GoStateResponse.Step::result)
                .filter(r -> r != null && r.error() != null)
                .map(r -> r.error().firstAction())
                .filter(a -> a != null && !a.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Go's journey lifecycle is {@code InProgress | Completed | Failed}, plus
     * {@code Paused} per the glossary; the notification payload spells the
     * failure case {@code Error}. Both failure spellings are treated the same.
     */
    static boolean isFailed(String goStatus) {
        return "Failed".equalsIgnoreCase(goStatus) || "Error".equalsIgnoreCase(goStatus);
    }

    private JourneyStatus mapStatus(String goStatus, boolean processing) {
        if ("Completed".equalsIgnoreCase(goStatus)) return JourneyStatus.COMPLETED;
        // Paused (awaiting an out-of-band event, e.g. a manual review) is not
        // waiting on the customer — reporting PendingInput would render an
        // empty form. It reads as still-running, which is what a processing
        // screen already shows.
        if (processing || "Paused".equalsIgnoreCase(goStatus)) return JourneyStatus.IN_PROGRESS;
        return JourneyStatus.PENDING_INPUT;
    }

    private Decision mapDecision(com.gbg.samples.onboarding.go.live.dto.GoResult result) {
        String classification = result == null ? null : result.outcomeClassification();
        if (classification == null) return Decision.REFER;
        return switch (classification.toLowerCase(Locale.ROOT)) {
            case "positive" -> Decision.PASS;
            case "negative" -> Decision.FAIL;
            default -> Decision.REFER;
        };
    }

    /**
     * A step's state. `result.status` says whether the module *ran* to
     * completion, not what it *decided* — that verdict is
     * {@code outcomeClassification}, on the step itself. A module can
     * complete and still decline (e.g. Document Authentication finishing and
     * finding the document fraudulent), so `status: complete` on its own is
     * not enough to call it a Pass; it only rules out the error/timeout/
     * still-running cases and then defers to the classification, the same as
     * the no-result fallback below.
     */
    private ModuleState mapModuleState(GoStateResponse.Step step) {
        GoStateResponse.StepResult result = step.result();
        if (result != null && result.status() != null) {
            return switch (result.status().toLowerCase(Locale.ROOT)) {
                case "error", "timeout" -> ModuleState.FAIL;
                case "pending" -> ModuleState.RUNNING;
                // Ran to completion — the verdict is the classification, not the run status.
                case "complete" -> classify(step.outcomeClassification(), ModuleState.REVIEW);
                default -> ModuleState.REVIEW;
            };
        }
        return classify(step.outcomeClassification(), ModuleState.RUNNING);
    }

    /** Positive/negative/other, falling back to {@code whenUnclassified} when Go hasn't reported one yet. */
    private ModuleState classify(String outcomeClassification, ModuleState whenUnclassified) {
        if (outcomeClassification == null) return whenUnclassified;
        return switch (outcomeClassification.toLowerCase(Locale.ROOT)) {
            case "positive" -> ModuleState.PASS;
            case "negative" -> ModuleState.FAIL;
            default -> ModuleState.REVIEW;
        };
    }

}
