package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

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
        public record Process(List<Step> steps, Journey journey) {
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

    /**
     * The journey's own name/version/timing, from wherever this response
     * carries it. Same nesting split as {@link #allSteps()}: the live
     * platform puts it at {@code context.process.journey}, not the root.
     */
    public Journey journeyInfo() {
        if (journey != null) return journey;
        if (context != null && context.process() != null) {
            return context.process().journey();
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journey(String id, String name, String version, String startedAt, String endedAt) {
    }

    /**
     * {@code durationMilliSec}, {@code startedAt} and {@code endedAt} live at
     * {@code process.step.*} — verified against a real completed run,
     * 2026-09-16 — not as siblings of {@code result} as an earlier version of
     * this class assumed (that guess always read back null).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String nodeId, String name, String outcome, String outcomeClassification, StepResult result,
                       StepProcess process) {
        public Step(String nodeId, String name, String outcome, String outcomeClassification, StepResult result) {
            this(nodeId, name, outcome, outcomeClassification, result, null);
        }

        private StepDetail detail() {
            return process == null ? null : process.step();
        }

        /** How long this module took to run, in milliseconds, or null if not yet finished. */
        public Long durationMilliSec() {
            StepDetail d = detail();
            return d == null ? null : d.durationMilliSec();
        }

        /** When this module finished (ISO-8601), or null if not yet finished. */
        public String endedAt() {
            StepDetail d = detail();
            return d == null ? null : d.endedAt();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepProcess(StepDetail step) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepDetail(String startedAt, String endedAt, Long durationMilliSec, String moduleName) {
    }

    /**
     * A step's own result. Present on the nested `result` object, not the
     * step root. {@code subject} is that module's own contribution to the
     * journey's subject data — e.g. Document Classification's own result
     * carries {@code subject.documents[0].classification}, which is where the
     * classified document type actually surfaces (verified against a real
     * completed run, 2026-09-16) — the top-level {@code GoResult} never
     * carries it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepResult(String status, String outcome, StepError error, Map<String, Object> subject) {
        public StepResult(String status, String outcome, StepError error) {
            this(status, outcome, error, null);
        }
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
