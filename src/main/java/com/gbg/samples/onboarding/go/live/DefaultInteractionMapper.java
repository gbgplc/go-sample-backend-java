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
import com.gbg.samples.onboarding.go.live.dto.GoInteractionFetchResponse;
import com.gbg.samples.onboarding.go.live.dto.GoStateResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Turns Go's raw, domain-element-shaped interaction into the front end's
 * opinionated {@code Interaction} DTO (screen kind, copy, field labels).
 *
 * This is a deliberately generic placeholder, not a finished mapping: Go
 * gives back which domain elements are outstanding, not English copy or a
 * screen kind — those are a product/copy decision per journey (front-end
 * handoff, section 6, "composition of the verification record" makes the
 * same point about the record endpoint). The mock client's fixtures show
 * what a *finished* mapping should read like; this class is the seam where
 * that real mapping — driven by the published journey's actual domain
 * elements, or a copy table keyed by domain element id — plugs in once a
 * journey exists to map against.
 */
@Component
public class DefaultInteractionMapper {

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
            return new Interaction(
                    interactionId, ScreenKind.PROCESSING, "Processing", null, "Running your checks",
                    "This usually takes a few seconds.", null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null
            );
        }

        List<String> outstanding = response.outstanding() == null ? List.of() : response.outstanding();

        // Go returns a single interaction listing everything still outstanding
        // at once; splitting that into screens is the client's job under
        // `delivery: "api"`. MeridianScreenPlan holds that decision.
        return MeridianScreenPlan.next(outstanding)
                .map(stage -> toStagedInteraction(interactionId, stage, outstanding))
                // Nothing outstanding but not yet Completed: modules are running.
                .orElseGet(() -> new Interaction(
                        interactionId, ScreenKind.PROCESSING, "Processing", null, "Running your checks",
                        "This usually takes a few seconds.", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null
                ));
    }

    /** One screen from the plan, with a rail showing where the customer has got to. */
    private Interaction toStagedInteraction(String interactionId, MeridianScreenPlan.Stage stage,
                                            List<String> outstanding) {
        boolean reachedCurrent = false;
        List<StagePlanEntry> rail = new java.util.ArrayList<>();
        for (String label : MeridianScreenPlan.stageLabels()) {
            StageState state;
            if (label.equals(stage.stage())) {
                state = StageState.ACTIVE;
                reachedCurrent = true;
            } else {
                state = reachedCurrent ? StageState.UPCOMING : StageState.DONE;
            }
            rail.add(new StagePlanEntry(label, state));
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
                stage.kind() == ScreenKind.FORM ? MeridianScreenPlan.fieldsFor(stage, outstanding) : null,
                null,
                stage.kind() == ScreenKind.CONSENT ? MeridianScreenPlan.consentChecks() : null,
                stage.modules(),
                null, null, null, null, null,
                rail
        );
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
     * A step's state, preferring its own result over the classification.
     *
     * `outcomeClassification` is absent on the nested per-module steps, which
     * would make every module read as still Running on a finished journey. The
     * step's `result.status` is populated there, and a module that errored is
     * reported as Fail — it did not pass, and showing it as still running on a
     * terminal screen would be worse.
     */
    private ModuleState mapModuleState(GoStateResponse.Step step) {
        GoStateResponse.StepResult result = step.result();
        if (result != null && result.status() != null) {
            return switch (result.status().toLowerCase(Locale.ROOT)) {
                case "complete" -> ModuleState.PASS;
                case "error", "timeout" -> ModuleState.FAIL;
                case "pending" -> ModuleState.RUNNING;
                default -> ModuleState.REVIEW;
            };
        }
        if (step.outcomeClassification() == null) return ModuleState.RUNNING;
        return switch (step.outcomeClassification().toLowerCase(Locale.ROOT)) {
            case "positive" -> ModuleState.PASS;
            case "negative" -> ModuleState.FAIL;
            default -> ModuleState.REVIEW;
        };
    }

    private String humanize(String domainElementId) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < domainElementId.length(); i++) {
            char c = domainElementId.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) out.append(' ');
            out.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }
}
