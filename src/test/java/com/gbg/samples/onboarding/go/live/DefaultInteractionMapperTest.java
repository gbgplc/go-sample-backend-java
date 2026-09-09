package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.StagePlanEntry;
import com.gbg.samples.onboarding.api.dto.StageState;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import com.gbg.samples.onboarding.go.live.dto.GoInteractionFetchResponse;
import com.gbg.samples.onboarding.go.live.dto.GoResult;
import com.gbg.samples.onboarding.go.live.dto.GoStateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultInteractionMapperTest {

    private static final ScreenPlanProperties.Stage DOCUMENT_STAGE = new ScreenPlanProperties.Stage(
            "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
            "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
            List.of("Passport"), List.of("Document Classification"));

    private static final ScreenPlanProperties.Stage BIOMETRICS_STAGE = new ScreenPlanProperties.Stage(
            "biometrics", ScreenKind.CAPTURE, "Selfie/", "Biometrics",
            "Take a selfie", "This proves you are the person in the document.", "Take selfie", "selfie",
            null, List.of("Liveness Verification"));

    private static final ScreenPlanProperties TWO_STAGE_PLAN =
            new ScreenPlanProperties(List.of(DOCUMENT_STAGE, BIOMETRICS_STAGE), List.of());

    private final DefaultInteractionMapper mapper = new DefaultInteractionMapper(TWO_STAGE_PLAN);

    // --- toInteraction: the configured screen plan drives which screen renders ---

    @Test
    void firstStageInPlanOrderWinsWhenSeveralAreOutstanding() {
        Interaction interaction = mapper.toInteraction(fetchResponseWithOutstanding(
                List.of("PrimaryDocument/side1Image", "Selfie/selfieImage")));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.CAPTURE);
        assertThat(interaction.stage()).isEqualTo("Document");
        assertThat(interaction.title()).isEqualTo("Scan your photo ID");
        assertThat(interaction.stagePlan()).containsExactly(
                new StagePlanEntry("Document", StageState.ACTIVE),
                new StagePlanEntry("Biometrics", StageState.UPCOMING));
    }

    @Test
    void laterStageRendersOnceEarlierOnesAreNoLongerOutstanding() {
        Interaction interaction = mapper.toInteraction(fetchResponseWithOutstanding(
                List.of("Selfie/selfieImage")));

        assertThat(interaction.stage()).isEqualTo("Biometrics");
        assertThat(interaction.stagePlan()).containsExactly(
                new StagePlanEntry("Document", StageState.DONE),
                new StagePlanEntry("Biometrics", StageState.ACTIVE));
    }

    @Test
    void anElementNoConfiguredStageClaimsFallsBackToAGenericFormInsteadOfStalling() {
        Interaction interaction = mapper.toInteraction(fetchResponseWithOutstanding(
                List.of("FullName/firstName")));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.FORM);
        assertThat(interaction.collects()).hasSize(1);
        assertThat(interaction.collects().get(0).name()).isEqualTo("FullName/firstName");
        assertThat(interaction.collects().get(0).label()).isEqualTo("First name");
    }

    @Test
    void anUnrecognisedElementWithNoKnownLabelIsDeCamelCased() {
        Interaction interaction = mapper.toInteraction(fetchResponseWithOutstanding(
                List.of("SomeNewModule/dateOfIssue")));

        assertThat(interaction.collects().get(0).label()).isEqualTo("Date of issue");
    }

    @Test
    void emptyPlanFallsBackToAGenericFormRatherThanCrashing() {
        DefaultInteractionMapper noPlanMapper = new DefaultInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

        Interaction interaction = noPlanMapper.toInteraction(fetchResponseWithOutstanding(
                List.of("PrimaryDocument/side1Image")));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.FORM);
    }

    private static GoInteractionFetchResponse fetchResponseWithOutstanding(List<String> outstanding) {
        return new GoInteractionFetchResponse(
                "instance-1", new GoInteractionFetchResponse.Journey("InProgress"), "int-1",
                null, false, outstanding, null, null);
    }

    // --- toRecord: module-level verdicts (mapModuleState) ---

    @Test
    void aModuleThatRanToCompletionButDeclinedIsReportedAsFailNotPass() {
        // Regression test: result.status "complete" only means the module ran —
        // the verdict is outcomeClassification, which here is negative.
        GoStateResponse.Step declined = new GoStateResponse.Step(
                "node1", "Document Authentication", null, "negative",
                new GoStateResponse.StepResult("complete", null, null));

        RecordResponse record = mapper.toRecord(stateResponseWith(declined));

        assertThat(record.moduleRuns()).hasSize(1);
        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.FAIL);
    }

    @Test
    void aModuleThatRanToCompletionAndPassedIsReportedAsPass() {
        GoStateResponse.Step passed = new GoStateResponse.Step(
                "node1", "Document Authentication", null, "positive",
                new GoStateResponse.StepResult("complete", null, null));

        RecordResponse record = mapper.toRecord(stateResponseWith(passed));

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.PASS);
    }

    @Test
    void aStillRunningModuleIsReportedAsRunningRegardlessOfAnyClassification() {
        GoStateResponse.Step running = new GoStateResponse.Step(
                "node1", "Facematch Verification", null, "negative",
                new GoStateResponse.StepResult("pending", null, null));

        RecordResponse record = mapper.toRecord(stateResponseWith(running));

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.RUNNING);
    }

    @Test
    void aModuleThatErroredIsReportedAsFail() {
        GoStateResponse.Step errored = new GoStateResponse.Step(
                "node1", "Document Classification", null, null,
                new GoStateResponse.StepResult("error", null, null));

        RecordResponse record = mapper.toRecord(stateResponseWith(errored));

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.FAIL);
    }

    @Test
    void aModuleWithNoResultYetFallsBackToItsOwnClassification() {
        GoStateResponse.Step notYetRun = new GoStateResponse.Step(
                "node1", "Liveness Verification", null, null, null);

        RecordResponse record = mapper.toRecord(stateResponseWith(notYetRun));

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.RUNNING);
    }

    private static GoStateResponse stateResponseWith(GoStateResponse.Step step) {
        return new GoStateResponse(
                "instance-1",
                "Completed",
                null,
                List.of(step),
                new GoResult(null, null, "positive", null, null),
                null);
    }
}
