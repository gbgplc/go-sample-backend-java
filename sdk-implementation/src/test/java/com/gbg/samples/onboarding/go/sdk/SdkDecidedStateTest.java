package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.StateFetchResponse;
import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The go-core-sdk sibling of api-implementation's {@code DecidedStateTest}.
 *
 * The shape a Northbank journey actually settles into, from a live fetch on
 * 2026-09-10: every module complete, an Evaluation node carrying
 * {@code outcomeClassification: neutral} and {@code outcome: "Decision:
 * Manual review"} — while {@code journey.status} stays InProgress. Go sends
 * the steps under {@code context.process.steps} and the decision as the
 * top-level {@code result}; in 0.2.0 both are untyped maps on
 * {@code StateFetchResponse}, built here by hand in that live shape.
 */
class SdkDecidedStateTest {

    private final SdkInteractionMapper mapper =
            new SdkInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

    private static Map<String, Object> doneStep(String name) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("result", Map.of("status", "complete"));
        return step;
    }

    /** The Evaluation node: no result object of its own, a classification instead, and no name — filtered from moduleRuns. */
    private static Map<String, Object> evaluationStep(String classification) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("outcomeClassification", classification);
        return step;
    }

    private static StateFetchResponse stateWith(String outcome, String outcomeClassification,
                                                List<Map<String, Object>> steps) {
        return StateFetchResponse.builder()
                .instanceId("i-1")
                .status("InProgress")
                .context(Map.of("process", Map.of("steps", steps)))
                .result(Map.of("outcome", outcome, "outcomeClassification", outcomeClassification))
                .build();
    }

    @Test
    void aNeutralEvaluationIsAReviewNotAModuleStillRunning() {
        StateFetchResponse state = stateWith("Decision: Manual review", "neutral",
                List.of(doneStep("Facematch Verification"), evaluationStep("neutral")));

        RecordResponse record = mapper.toRecord(state);

        assertThat(record.moduleRuns())
                .extracting(r -> r.state())
                .doesNotContain(ModuleState.RUNNING);
        assertThat(record.decision()).isEqualTo(Decision.REFER);
        assertThat(record.title()).isEqualTo("With our team");
        assertThat(mapper.carriesRealResult(state)).isTrue();
    }

    @Test
    void aPositiveEvaluationReadsAsIdentityVerified() {
        StateFetchResponse state = stateWith("Decision: Accept", "positive",
                List.of(doneStep("Facematch Verification"), evaluationStep("positive")));

        RecordResponse record = mapper.toRecord(state);

        assertThat(record.decision()).isEqualTo(Decision.PASS);
        assertThat(record.title()).isEqualTo("Verification complete");
        assertThat(record.body()).isEqualTo("Your identity has been verified.");
    }

    @Test
    void noResultYetIsNotADecision() {
        StateFetchResponse state = StateFetchResponse.builder()
                .instanceId("i-1")
                .status("InProgress")
                .build();

        assertThat(mapper.carriesRealResult(state)).isFalse();
        assertThat(mapper.toRecord(state).moduleRuns()).isEmpty();
    }

    /**
     * Go documents {@code result.status} as authoritative: a journey can pass
     * its evaluation and then fail or be abandoned before finishing, leaving a
     * positive classification beside {@code status: "error"}.
     */
    @Test
    void aFailedResultStatusBeatsAPositiveClassification() {
        StateFetchResponse state = StateFetchResponse.builder()
                .instanceId("i-1")
                .status("InProgress")
                .context(Map.of("process", Map.of("steps", List.of(doneStep("Facematch Verification")))))
                .result(Map.of("status", "error", "outcomeClassification", "positive"))
                .build();

        RecordResponse record = mapper.toRecord(state);

        assertThat(mapper.resultFailed(state)).isTrue();
        assertThat(record.decision()).isEqualTo(Decision.FAIL);
        assertThat(record.systemError()).isTrue();
        assertThat(record.title()).isEqualTo("We could not run your checks");
    }
}
