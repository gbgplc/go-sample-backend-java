package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import com.gbg.samples.onboarding.go.live.dto.GoInteractionFetchResponse;
import com.gbg.samples.onboarding.go.live.dto.GoResult;
import com.gbg.samples.onboarding.go.live.dto.GoStateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape a Northbank journey actually settles into, from a live fetch on
 * 2026-09-10: every module complete, an Evaluation node carrying
 * {@code outcomeClassification: neutral} and {@code outcome: "Decision:
 * Manual review"} — while {@code journey.status} stays InProgress and
 * {@code result.status} stays pending.
 *
 * The customer is owed the decision at that point. Waiting for the status to
 * reach Completed leaves them on "Running your checks" indefinitely, with the
 * verdict sitting in the very response being polled.
 */
class DecidedStateTest {

    private final DefaultInteractionMapper mapper =
            new DefaultInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

    private static GoStateResponse.Step done(String name) {
        return new GoStateResponse.Step(name, name, null, null,
                new GoStateResponse.StepResult("complete", null, null));
    }

    /** The Evaluation node: no result object of its own, a classification instead. */
    private static GoStateResponse.Step evaluation(String classification) {
        return new GoStateResponse.Step("eval", null, null, classification, null);
    }

    @Test
    void aNeutralEvaluationIsAReviewNotAModuleStillRunning() {
        GoStateResponse state = new GoStateResponse(
                "i-1", "InProgress", null,
                List.of(done("Facematch Verification"), evaluation("neutral")),
                new GoResult("Decision: Manual review", "pending", "neutral", null, null),
                null);

        RecordResponse record = mapper.toRecord(state);

        assertThat(record.moduleRuns())
                .extracting(r -> r.state())
                .doesNotContain(ModuleState.RUNNING);
        assertThat(record.decision()).isEqualTo(Decision.REFER);
        assertThat(record.title()).isEqualTo("With our team");
    }

    @Test
    void aPositiveEvaluationReadsAsIdentityVerified() {
        GoStateResponse state = new GoStateResponse(
                "i-1", "InProgress", null,
                List.of(done("Facematch Verification"), evaluation("positive")),
                new GoResult("Decision: Accept", "pending", "positive", null, null),
                null);

        RecordResponse record = mapper.toRecord(state);

        assertThat(record.decision()).isEqualTo(Decision.PASS);
        assertThat(record.title()).isEqualTo("Verification complete");
        assertThat(record.body()).isEqualTo("Your identity has been verified.");
    }

    /**
     * Go documents {@code result.status} as authoritative: a journey can pass
     * its evaluation and then fail or be abandoned before finishing, leaving a
     * positive classification beside {@code status: "error"}.
     */
    @Test
    void aFailedResultStatusBeatsAPositiveClassification() {
        GoStateResponse state = new GoStateResponse(
                "i-1", "InProgress", null,
                List.of(done("Facematch Verification"), evaluation("positive")),
                new GoResult("Decision: Accept", "error", "positive", null, null),
                null);

        RecordResponse record = mapper.toRecord(state);

        assertThat(record.decision()).isEqualTo(Decision.FAIL);
        assertThat(record.systemError()).isTrue();
        assertThat(record.title()).isEqualTo("We could not run your checks");
        assertThat(record.body()).isEqualTo("We could not run your checks. Nothing has been decided about you — please try again.");
    }

    /** The result of a deadlocked journey, from gbggo4-demo (2026-10-29, sandbox_QPgHrNztUswy5tZySLOUfe). */
    private static GoResult abandoned() {
        return new GoResult(null, "abandoned", null,
                List.of(Map.of("code", "MODULE_DECLARED_OUTPUT_MISSING"), Map.of("code", "JOURNEY_ABANDONED")), null);
    }

    /** A platform failure, not a verdict on the customer, so never "Declined". */
    @Test
    void anAbandonedJourneyIsASystemErrorNotADecline() {
        GoStateResponse state = new GoStateResponse(
                "sandbox_QPgHrNztUswy5tZySLOUfe", "Error", null,
                List.of(done("Document Classification")),
                abandoned(),
                null);

        RecordResponse record = mapper.toRecord(state);

        assertThat(record.decision()).isEqualTo(Decision.FAIL);
        assertThat(record.systemError()).isTrue();
        assertThat(record.title()).isEqualTo("We could not run your checks");
    }

    /** The interaction screen agrees with the record, whatever the journey status says. */
    @Test
    void aCompletedJourneyWithAFailedResultIsNotAPass() {
        GoInteractionFetchResponse fetch = new GoInteractionFetchResponse(
                "i-1", new GoInteractionFetchResponse.Journey("Completed"), null, null, false, List.of(), List.of(),
                new GoResult("Decision: Accept", "error", "positive", null, null));

        Interaction interaction = mapper.toInteraction(fetch, Set.of());

        assertThat(interaction.kind()).isEqualTo(ScreenKind.RESULT);
        assertThat(interaction.decision()).isEqualTo(Decision.FAIL);
        assertThat(interaction.title()).isNotEqualTo("Verification complete");
    }
}
