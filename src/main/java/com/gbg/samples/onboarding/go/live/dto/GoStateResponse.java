package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST {baseUrl}journey/state/fetch response — see
 * /docs/go-v2/api-reference/endpoint/fetch-journey-state.
 *
 * Per-module results are nested at {@code context.process.steps}, not at the
 * root: the top-level {@code steps} below stays for any response that does put
 * them there, and {@link #allSteps()} reads whichever is populated. Reading
 * only the root silently yields no modules at all, which reaches the customer
 * as a decision screen listing no checks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoStateResponse(String instanceId, String status, Journey journey, List<Step> steps, GoResult result,
                              Context context) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Context(Process process) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Process(List<Step> steps) {
        }
    }

    /** The module steps, from wherever this response carries them. */
    public List<Step> allSteps() {
        if (steps != null && !steps.isEmpty()) return steps;
        if (context != null && context.process() != null && context.process().steps() != null) {
            return context.process().steps();
        }
        return List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journey(String id, String name, String version, String startedAt, String endedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String nodeId, String name, String outcome, String outcomeClassification, StepResult result) {
    }

    /** A step's own result. Present on the nested `result` object, not the step root. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepResult(String status, String outcome, StepError error) {
    }

    /**
     * What a module reports when it could not run — distinct from a module
     * that ran and declined. Go states the problem and what to do about it
     * ("An error occurred during document classification" / "Please verify the
     * document image and try again"), which is worth far more to the customer
     * than a generic failure message.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepError(List<ErrorItem> errors, String correlationId) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ErrorItem(String error, String location, String problem, String action, String code) {
        }

        /** The first action Go suggests, or null when it offered none. */
        public String firstAction() {
            if (errors == null || errors.isEmpty()) return null;
            return errors.get(0).action();
        }
    }
}
