package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import com.gbg.samples.onboarding.go.live.dto.GoResult;
import com.gbg.samples.onboarding.go.live.dto.GoStateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
