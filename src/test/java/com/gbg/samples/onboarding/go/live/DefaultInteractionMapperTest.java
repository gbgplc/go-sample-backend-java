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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultInteractionMapperTest {

    private static final ScreenPlanProperties.Stage DOCUMENT_STAGE = new ScreenPlanProperties.Stage(
            "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
            "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
            List.of("Passport"), List.of("Document Classification"), null, null);

    private static final ScreenPlanProperties.Stage BIOMETRICS_STAGE = new ScreenPlanProperties.Stage(
            "biometrics", ScreenKind.CAPTURE, "Selfie/", "Biometrics",
            "Take a selfie", "This proves you are the person in the document.", "Take selfie", "selfie",
            null, List.of("Liveness Verification"), null, null);

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

    // --- Progress against a journey whose `outstanding` never shrinks ---

    /**
     * The Northbank journey on gbggo4-demo returns a byte-identical
     * `outstanding` after a successful submit — it declares what the journey
     * collects rather than what is still missing. Selecting on it alone
     * re-picks the first stage forever, which reaches the customer as a
     * Continue button that does nothing.
     */
    @Test
    void aSubmittedStageIsNotShownAgainWhenOutstandingNeverShrinks() {
        List<String> unchanging = List.of("PrimaryDocument/side1Image", "Selfie/selfieImage");

        Interaction first = mapper.toInteraction(fetchResponseWithOutstanding(unchanging), Set.of());
        assertThat(first.stage()).isEqualTo("Document");

        Interaction afterDocument = mapper.toInteraction(
                fetchResponseWithOutstanding(unchanging), Set.of("document"));

        assertThat(afterDocument.stage()).isEqualTo("Biometrics");
        assertThat(afterDocument.stagePlan()).containsExactly(
                new StagePlanEntry("Document", StageState.DONE),
                new StagePlanEntry("Biometrics", StageState.ACTIVE));
    }

    @Test
    void everyStageSubmittedLeavesNothingToRenderAndFallsThroughToProcessing() {
        Interaction interaction = mapper.toInteraction(
                fetchResponseWithOutstanding(List.of("PrimaryDocument/side1Image", "Selfie/selfieImage")),
                Set.of("document", "biometrics"));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.PROCESSING);
    }

    /**
     * Northbank's journey collects its document lazily: `outstanding` never
     * names PrimaryDocument/, yet Go accepts a document submission. Selecting
     * on `outstanding` alone skips the ID scan and sends the customer from
     * address straight to selfie.
     */
    @Test
    void anAlwaysCollectStageRendersEvenThoughGoNeverListsItsElements() {
        ScreenPlanProperties.Stage lazyDocument = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                List.of("Passport"), List.of("Document Classification"), true, null);
        DefaultInteractionMapper lazyMapper = new DefaultInteractionMapper(
                new ScreenPlanProperties(List.of(lazyDocument, BIOMETRICS_STAGE), List.of()));

        Interaction interaction = lazyMapper.toInteraction(
                fetchResponseWithOutstanding(List.of("Selfie/selfieImage")), Set.of());

        assertThat(interaction.stage()).isEqualTo("Document");
        assertThat(interaction.stagePlan()).containsExactly(
                new StagePlanEntry("Document", StageState.ACTIVE),
                new StagePlanEntry("Biometrics", StageState.UPCOMING));

        // And it advances once submitted, rather than repeating.
        Interaction next = lazyMapper.toInteraction(
                fetchResponseWithOutstanding(List.of("Selfie/selfieImage")), Set.of("document"));
        assertThat(next.stage()).isEqualTo("Biometrics");
    }

    @Test
    void anOrdinaryStageIsStillSkippedWhenGoDoesNotListItsElements() {
        // The default, and what keeps a retired module from rendering a dead
        // screen: only always-collect opts out of the outstanding check.
        Interaction interaction = mapper.toInteraction(
                fetchResponseWithOutstanding(List.of("Selfie/selfieImage")), Set.of());

        assertThat(interaction.stage()).isEqualTo("Biometrics");
    }

    // --- currentCaptureIsDocument: which capture an attachmentRef is ---

    /**
     * The browser posts {@code {attachmentRef}} with no hint of which capture
     * it is. Classifying that by looking for PrimaryDocument/ in `outstanding`
     * sends a document photo to subject.biometrics on a journey that collects
     * the document lazily — Document Classification never receives an image.
     */
    @Test
    void aLazilyCollectedDocumentCaptureIsNotMistakenForASelfie() {
        ScreenPlanProperties.Stage lazyDocument = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                List.of("Passport"), List.of("Document Classification"), true, null);
        DefaultInteractionMapper lazyMapper = new DefaultInteractionMapper(
                new ScreenPlanProperties(List.of(lazyDocument, BIOMETRICS_STAGE), List.of()));

        // Go lists only the selfie, yet the document screen is the one showing.
        assertThat(lazyMapper.currentCaptureIsDocument(List.of("Selfie/selfieImage"), Set.of())).isTrue();
        // Once the document is submitted, the same call classifies the selfie.
        assertThat(lazyMapper.currentCaptureIsDocument(List.of("Selfie/selfieImage"), Set.of("document"))).isFalse();
    }

    @Test
    void captureClassificationDefersToOutstandingWhenNoStageIsCurrent() {
        DefaultInteractionMapper noPlanMapper =
                new DefaultInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

        assertThat(noPlanMapper.currentCaptureIsDocument(List.of("PrimaryDocument/side1Image"), Set.of())).isNull();
    }

    // --- stageFor: which stage a submit answers ---

    @Test
    void aPrefixedFormFieldIdentifiesItsStage() {
        assertThat(mapper.stageFor(List.of("PrimaryDocument/side1Image"), Set.of())).isEqualTo("document");
    }

    @Test
    void aShortCaptureFieldNameIdentifiesItsStage() {
        assertThat(mapper.stageFor(List.of("selfieImage"), Set.of())).isEqualTo("biometrics");
        assertThat(mapper.stageFor(List.of("documentImage"), Set.of())).isEqualTo("document");
    }

    @Test
    void anAlreadyCompletedStageIsNotMatchedAgain() {
        assertThat(mapper.stageFor(List.of("selfieImage"), Set.of("biometrics"))).isNull();
    }

    @Test
    void anUnrecognisedSubmitMatchesNoStage() {
        assertThat(mapper.stageFor(List.of("somethingElse"), Set.of())).isNull();
    }

    /**
     * A screen whose fields are all optional, all left blank, submits nothing
     * — Ridgeline's sign-up offers four such fields. There are no field names
     * to match on, but the customer pressed Continue on the screen they were
     * shown, and without crediting it the journey re-picks that same screen:
     * a Continue button that does nothing on the one screen a customer is
     * entitled to skip.
     */
    @Test
    void anEmptySubmitCreditsTheFormScreenTheCustomerWasOn() {
        ScreenPlanProperties.Stage optionalForm = new ScreenPlanProperties.Stage(
                "personal", ScreenKind.FORM, "Gender", "Sign up",
                "About you", "Tell us about yourself.", "Continue", null,
                null, null, null, null);
        DefaultInteractionMapper formMapper = new DefaultInteractionMapper(
                new ScreenPlanProperties(List.of(optionalForm, BIOMETRICS_STAGE), List.of()));

        assertThat(formMapper.stageFor(List.of(), Set.of())).isEqualTo("personal");
        // Once credited it is not offered again, so a second empty submit
        // cannot silently re-complete it.
        assertThat(formMapper.stageFor(List.of(), Set.of("personal"))).isNull();
    }

    @Test
    void anEmptySubmitNeverCreditsACaptureOrConsentScreen() {
        // Both always submit something — an image or a checkbox set — so an
        // empty submit did not come from one, and crediting the next capture
        // stage would skip a screen the customer has not completed.
        assertThat(mapper.stageFor(List.of(), Set.of())).isNull();
    }

    @Test
    void emptyPlanFallsBackToAGenericFormRatherThanCrashing() {
        DefaultInteractionMapper noPlanMapper = new DefaultInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

        Interaction interaction = noPlanMapper.toInteraction(fetchResponseWithOutstanding(
                List.of("PrimaryDocument/side1Image")));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.FORM);
    }

    private static GoInteractionFetchResponse fetchResponseWithOutstanding(List<String> outstanding) {
        return fetchResponse(outstanding, null);
    }

    private static GoInteractionFetchResponse fetchResponse(List<String> outstanding, List<String> instructions) {
        return new GoInteractionFetchResponse(
                "instance-1", new GoInteractionFetchResponse.Journey("InProgress"), "int-1",
                null, false, outstanding, instructions, null);
    }

    // --- Side 2: the back of a two-sided document ---

    /**
     * Classification reads side 1, finds a two-sided document type, and asks
     * for the back — after the document stage was submitted. Skipping it (the
     * stage is "completed") shows the selfie while Go waits for a side that
     * never arrives, and the journey deadlocks in collection.
     */
    @Test
    void goAskingForTheBackOfTheDocumentReopensTheDocumentStage() {
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("Selfie/selfieImage", "PrimaryDocument/side2Image"), List.of("Side2Required")),
                Set.of("document"));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.CAPTURE);
        assertThat(interaction.stage()).isEqualTo("Document");
        assertThat(interaction.title()).isEqualTo("Now the other side");
        assertThat(interaction.captureType()).isEqualTo("document-back");
    }

    @Test
    void theInstructionAloneIsEnoughToAskForTheBack() {
        // Go carries Side2Required without necessarily listing side2Image.
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("Selfie/selfieImage"), List.of("Side2Required")), Set.of("document"));

        assertThat(interaction.captureType()).isEqualTo("document-back");
    }

    @Test
    void lazySide2CollectionRequiredIsNotARequestForTheBack() {
        // The pre-capture state, present from the first fetch. Treating it as
        // a request would show the back screen before the front was taken.
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("PrimaryDocument/side1Image", "Selfie/selfieImage"),
                        List.of("LazySide2CollectionRequired")),
                Set.of());

        assertThat(interaction.title()).isEqualTo("Scan your photo ID");
        assertThat(interaction.captureType()).isEqualTo("document");
    }

    /**
     * The answer to "does this document have a back?" does not arrive with
     * the submit — for a few seconds the fetch still carries the pre-decision
     * LazySide2CollectionRequired. Advancing on that stale instruction shows
     * the selfie screen, and the side-2 submit that follows the selfie
     * replaces subject.biometrics with the document's anchorImage, leaving
     * Facematch with no selfie to compare.
     */
    @Test
    void theDocumentStageHoldsWhileClassificationHasNotAnsweredYet() {
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("Selfie/selfieImage"), List.of("LazySide2CollectionRequired")),
                Set.of("document"));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.PROCESSING);
        assertThat(interaction.stage()).isNotEqualTo("Biometrics");
    }

    @Test
    void theHoldOnlyAppliesOnceSideOneHasBeenSubmitted() {
        // Before the document is captured the same instruction means "this
        // journey can take a second side", not "an answer is pending".
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("PrimaryDocument/side1Image", "Selfie/selfieImage"),
                        List.of("LazySide2CollectionRequired")),
                Set.of());

        assertThat(interaction.stage()).isEqualTo("Document");
        assertThat(interaction.captureType()).isEqualTo("document");
    }

    @Test
    void sideTwoDoneLeavesTheJourneyMovingOn() {
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("Selfie/selfieImage"), List.of("Side2Done")), Set.of("document"));

        assertThat(interaction.stage()).isEqualTo("Biometrics");
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
