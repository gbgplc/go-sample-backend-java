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
            Map.entry("MobilePhone/number", "Mobile number")
    );

    private final ScreenPlanProperties screenPlan;

    public DefaultInteractionMapper(ScreenPlanProperties screenPlan) {
        this.screenPlan = screenPlan;
    }

    public Interaction toInteraction(GoInteractionFetchResponse response) {
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

        List<String> outstanding = response.outstanding() == null ? List.of() : response.outstanding();

        // Nothing outstanding but not yet Completed: modules are running.
        if (outstanding.isEmpty()) {
            return processingInteraction(interactionId);
        }

        // Go returns a single interaction listing everything still outstanding
        // at once; splitting that into screens is the client's job under
        // `delivery: "api"`. The configured screen plan holds that decision —
        // first stage in order whose elements are still outstanding wins.
        return screenPlan.stages().stream()
                .filter(stage -> stage.claims(outstanding))
                .findFirst()
                .map(stage -> toStagedInteraction(interactionId, stage, outstanding))
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
                                            List<String> outstanding) {
        boolean reachedCurrent = false;
        List<StagePlanEntry> rail = new java.util.ArrayList<>();
        for (ScreenPlanProperties.Stage planStage : screenPlan.stages()) {
            StageState state;
            if (planStage.stage().equals(stage.stage())) {
                state = StageState.ACTIVE;
                reachedCurrent = true;
            } else {
                state = reachedCurrent ? StageState.UPCOMING : StageState.DONE;
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
                stage.kind() == ScreenKind.FORM ? fieldsFor(stage, outstanding) : null,
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
    private List<FieldSchema> fieldsFor(ScreenPlanProperties.Stage stage, List<String> outstanding) {
        return outstanding.stream()
                .filter(o -> o.startsWith(stage.prefix()))
                .map(o -> FieldSchema.of(o, label(o), null))
                .toList();
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
                null
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
