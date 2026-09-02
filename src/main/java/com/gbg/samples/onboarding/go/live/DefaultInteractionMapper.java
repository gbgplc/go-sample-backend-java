package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.FieldSchema;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ModuleRun;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
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
        JourneyStatus status = mapStatus(response.journey() == null ? null : response.journey().status(), response.processing());
        String interactionId = response.interactionId() != null ? response.interactionId() : response.instanceId();

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

        List<FieldSchema> fields = (response.outstanding() == null ? List.<String>of() : response.outstanding()).stream()
                .map(id -> FieldSchema.of(id, humanize(id), null))
                .toList();

        return new Interaction(
                interactionId, ScreenKind.FORM, "Details", null, "A few more details",
                "We need the following to continue.", null, "Continue", null, null, null,
                fields, null, null, null, null, null, null, null, null, null
        );
    }

    public RecordResponse toRecord(GoStateResponse response) {
        Decision decision = mapDecision(response.result());
        List<ModuleRun> moduleRuns = response.steps() == null ? List.of() : response.steps().stream()
                .map(step -> new ModuleRun(
                        step.name() != null ? step.name() : step.nodeId(),
                        mapModuleState(step.outcomeClassification())))
                .toList();
        String title = switch (decision) {
            case PASS -> "Verification complete";
            case FAIL -> "We could not complete your verification";
            case REFER -> "With our team";
        };
        return new RecordResponse(
                decision,
                title,
                "",
                response.result() == null ? "" : String.valueOf(response.result().outcome()),
                "Done",
                moduleRuns,
                List.of(),
                null
        );
    }

    private JourneyStatus mapStatus(String goStatus, boolean processing) {
        if ("Completed".equalsIgnoreCase(goStatus)) return JourneyStatus.COMPLETED;
        if (processing) return JourneyStatus.IN_PROGRESS;
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

    private ModuleState mapModuleState(String outcomeClassification) {
        if (outcomeClassification == null) return ModuleState.RUNNING;
        return switch (outcomeClassification.toLowerCase(Locale.ROOT)) {
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
