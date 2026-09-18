package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.operations.GetJourneyStateResponseBody;
import com.gbg.gocore.models.operations.GetJourneyStateStatus;
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
 * Manual review"} — while {@code journey.status} stays InProgress. On the
 * raw-HTTP client this arrived as a typed {@code GoStateResponse} with a
 * top-level {@code result} field and a {@code steps} list. On the SDK,
 * {@code GetJourneyStateResponseBody} has no typed field for either — see
 * {@link SdkInteractionMapper}'s class javadoc — so this test builds the same
 * shape by hand into the untyped {@code data} catch-all, exactly the paths
 * {@code SdkInteractionMapper.toRecord} reads. This exercises the same
 * business rule as the original (a per-step "Evaluation" node with a
 * classification is a decision, not a still-running module) against the
 * SDK's real builder-constructed response type; it does not confirm that a
 * live response actually nests this data the same way — that is the one
 * thing that cannot be confirmed without live credentials (see this module's
 * README, "Known gaps").
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

    private static GetJourneyStateResponseBody stateWith(String outcome, String outcomeClassification,
                                                           List<Map<String, Object>> steps) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("steps", steps);
        data.put("result", Map.of("outcome", outcome, "outcomeClassification", outcomeClassification));
        return GetJourneyStateResponseBody.builder()
                .instanceId("i-1")
                .status(GetJourneyStateStatus.IN_PROGRESS)
                .data(data)
                .build();
    }

    @Test
    void aNeutralEvaluationIsAReviewNotAModuleStillRunning() {
        GetJourneyStateResponseBody state = stateWith("Decision: Manual review", "neutral",
                List.of(doneStep("Facematch Verification"), evaluationStep("neutral")));

        RecordResponse record = mapper.toRecord(state, Map.of());

        assertThat(record.moduleRuns())
                .extracting(r -> r.state())
                .doesNotContain(ModuleState.RUNNING);
        assertThat(record.decision()).isEqualTo(Decision.REFER);
        assertThat(record.title()).isEqualTo("With our team");
    }

    @Test
    void aPositiveEvaluationReadsAsIdentityVerified() {
        GetJourneyStateResponseBody state = stateWith("Decision: Accept", "positive",
                List.of(doneStep("Facematch Verification"), evaluationStep("positive")));

        RecordResponse record = mapper.toRecord(state, Map.of());

        assertThat(record.decision()).isEqualTo(Decision.PASS);
        assertThat(record.title()).isEqualTo("Verification complete");
        assertThat(record.body()).isEqualTo("Your identity has been verified.");
    }
}
