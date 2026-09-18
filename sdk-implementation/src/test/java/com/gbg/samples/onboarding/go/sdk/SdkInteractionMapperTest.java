package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.operations.GetJourneyStateResponseBody;
import com.gbg.gocore.models.operations.GetJourneyStateStatus;
import com.gbg.gocore.models.operations.Journey1;
import com.gbg.gocore.models.operations.JourneyStatus1;
import com.gbg.gocore.models.operations.ResponseBody1;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.StagePlanEntry;
import com.gbg.samples.onboarding.api.dto.StageState;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The go-core-sdk sibling of api-implementation's
 * {@code DefaultInteractionMapperTest} — every business-rule assertion from
 * that file is ported here one at a time against SDK-shaped fixtures built
 * through the real generated builders, per the porting task's checklist
 * requirement. None were dropped.
 *
 * <h2>Two families of fixture</h2>
 * <ul>
 *   <li>{@code toInteraction} tests build a real {@link ResponseBody1} via
 *       its builder — this is the shape the spike fully confirmed
 *       (instanceId/interactionId/journey/interaction/instructions/outstanding
 *       all present, typed), so these fixtures are as faithful as the
 *       original raw-HTTP ones.</li>
 *   <li>{@code toRecord} tests build a {@link GetJourneyStateResponseBody}
 *       with the step/journey/result shape written into its untyped
 *       {@code data} map by hand — {@code GetJourneyStateResponseBody} has no
 *       typed field for any of it (see {@link SdkInteractionMapper}'s class
 *       javadoc). These fixtures encode the same JSON paths the raw
 *       {@code GoStateResponse} record used to deserialize automatically;
 *       they prove the mapper's map-walking logic is internally consistent,
 *       not that a live Go response is actually shaped this way — that is
 *       the one thing that cannot be confirmed without live credentials (see
 *       this module's README, "Known gaps").</li>
 * </ul>
 */
class SdkInteractionMapperTest {

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

    private final SdkInteractionMapper mapper = new SdkInteractionMapper(TWO_STAGE_PLAN);

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

    @Test
    void anAlwaysCollectStageRendersEvenThoughGoNeverListsItsElements() {
        ScreenPlanProperties.Stage lazyDocument = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                List.of("Passport"), List.of("Document Classification"), true, null);
        SdkInteractionMapper lazyMapper = new SdkInteractionMapper(
                new ScreenPlanProperties(List.of(lazyDocument, BIOMETRICS_STAGE), List.of()));

        Interaction interaction = lazyMapper.toInteraction(
                fetchResponseWithOutstanding(List.of("Selfie/selfieImage")), Set.of());

        assertThat(interaction.stage()).isEqualTo("Document");
        assertThat(interaction.stagePlan()).containsExactly(
                new StagePlanEntry("Document", StageState.ACTIVE),
                new StagePlanEntry("Biometrics", StageState.UPCOMING));

        Interaction next = lazyMapper.toInteraction(
                fetchResponseWithOutstanding(List.of("Selfie/selfieImage")), Set.of("document"));
        assertThat(next.stage()).isEqualTo("Biometrics");
    }

    @Test
    void anOrdinaryStageIsStillSkippedWhenGoDoesNotListItsElements() {
        Interaction interaction = mapper.toInteraction(
                fetchResponseWithOutstanding(List.of("Selfie/selfieImage")), Set.of());

        assertThat(interaction.stage()).isEqualTo("Biometrics");
    }

    // --- currentCaptureIsDocument: which capture an attachmentRef is ---
    // (No Go-response-shaped input at all — same as the original, ported unchanged.)

    @Test
    void aLazilyCollectedDocumentCaptureIsNotMistakenForASelfie() {
        ScreenPlanProperties.Stage lazyDocument = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                List.of("Passport"), List.of("Document Classification"), true, null);
        SdkInteractionMapper lazyMapper = new SdkInteractionMapper(
                new ScreenPlanProperties(List.of(lazyDocument, BIOMETRICS_STAGE), List.of()));

        assertThat(lazyMapper.currentCaptureIsDocument(List.of("Selfie/selfieImage"), Set.of())).isTrue();
        assertThat(lazyMapper.currentCaptureIsDocument(List.of("Selfie/selfieImage"), Set.of("document"))).isFalse();
    }

    @Test
    void captureClassificationDefersToOutstandingWhenNoStageIsCurrent() {
        SdkInteractionMapper noPlanMapper =
                new SdkInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

        assertThat(noPlanMapper.currentCaptureIsDocument(List.of("PrimaryDocument/side1Image"), Set.of())).isNull();
    }

    // --- stageFor: which stage a submit answers ---
    // (Also no Go-response-shaped input — ported unchanged.)

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

    @Test
    void anEmptySubmitCreditsTheFormScreenTheCustomerWasOn() {
        ScreenPlanProperties.Stage optionalForm = new ScreenPlanProperties.Stage(
                "personal", ScreenKind.FORM, "Gender", "Sign up",
                "About you", "Tell us about yourself.", "Continue", null,
                null, null, null, null);
        SdkInteractionMapper formMapper = new SdkInteractionMapper(
                new ScreenPlanProperties(List.of(optionalForm, BIOMETRICS_STAGE), List.of()));

        assertThat(formMapper.stageFor(List.of(), Set.of())).isEqualTo("personal");
        assertThat(formMapper.stageFor(List.of(), Set.of("personal"))).isNull();
    }

    @Test
    void anEmptySubmitNeverCreditsACaptureOrConsentScreen() {
        assertThat(mapper.stageFor(List.of(), Set.of())).isNull();
    }

    @Test
    void emptyPlanFallsBackToAGenericFormRatherThanCrashing() {
        SdkInteractionMapper noPlanMapper = new SdkInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

        Interaction interaction = noPlanMapper.toInteraction(fetchResponseWithOutstanding(
                List.of("PrimaryDocument/side1Image")));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.FORM);
    }

    private static ResponseBody1 fetchResponseWithOutstanding(List<String> outstanding) {
        return fetchResponse(outstanding, null);
    }

    private static ResponseBody1 fetchResponse(List<String> outstanding, List<String> instructions) {
        return ResponseBody1.builder()
                .instanceId("instance-1")
                .interactionId("int-1")
                .journey(new Journey1(JourneyStatus1.IN_PROGRESS))
                .interaction(new com.gbg.gocore.models.operations.Interaction(List.of(), List.of(), "gr-1"))
                .outstanding(outstanding)
                .instructions(instructions)
                .build();
    }

    // --- Side 2: the back of a two-sided document ---

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
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("Selfie/selfieImage"), List.of("Side2Required")), Set.of("document"));

        assertThat(interaction.captureType()).isEqualTo("document-back");
    }

    @Test
    void lazySide2CollectionRequiredIsNotARequestForTheBack() {
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("PrimaryDocument/side1Image", "Selfie/selfieImage"),
                        List.of("LazySide2CollectionRequired")),
                Set.of());

        assertThat(interaction.title()).isEqualTo("Scan your photo ID");
        assertThat(interaction.captureType()).isEqualTo("document");
    }

    @Test
    void theDocumentStageHoldsWhileClassificationHasNotAnsweredYet() {
        Interaction interaction = mapper.toInteraction(
                fetchResponse(List.of("Selfie/selfieImage"), List.of("LazySide2CollectionRequired")),
                Set.of("document"));

        assertThat(interaction.kind()).isEqualTo(ScreenKind.PROCESSING);
        assertThat(interaction.stage()).isNotEqualTo("Biometrics");
        assertThat(interaction.title()).isEqualTo("Verifying document type");
    }

    @Test
    void theHoldOnlyAppliesOnceSideOneHasBeenSubmitted() {
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

    // ------------------------------------------------------------------
    // toRecord: module-level verdicts (mapModuleState) — built from the
    // untyped `data` map, see class javadoc.
    // ------------------------------------------------------------------

    /** One raw step entry: {@code {name, outcomeClassification, result: {status, outcome}}}, only non-null keys set. */
    private static Map<String, Object> stepMap(String name, String outcomeClassification,
                                                String resultStatus, String resultOutcome) {
        Map<String, Object> step = new LinkedHashMap<>();
        if (name != null) step.put("name", name);
        if (outcomeClassification != null) step.put("outcomeClassification", outcomeClassification);
        if (resultStatus != null || resultOutcome != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (resultStatus != null) result.put("status", resultStatus);
            if (resultOutcome != null) result.put("outcome", resultOutcome);
            step.put("result", result);
        }
        return step;
    }

    private static Map<String, Object> stepMapWithTiming(String name, String outcomeClassification,
                                                           String resultStatus, String resultOutcome,
                                                           String startedAt, String endedAt, long durationMs) {
        Map<String, Object> step = stepMap(name, outcomeClassification, resultStatus, resultOutcome);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("startedAt", startedAt);
        detail.put("endedAt", endedAt);
        detail.put("durationMilliSec", durationMs);
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("step", detail);
        step.put("process", process);
        return step;
    }

    private static Map<String, Object> journeyMap(String name, String version, String startedAt, String endedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("startedAt", startedAt);
        m.put("endedAt", endedAt);
        return m;
    }

    private static Map<String, Object> resultMap(String outcome, String outcomeClassification) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome);
        m.put("outcomeClassification", outcomeClassification);
        return m;
    }

    private static GetJourneyStateResponseBody buildState(String status, List<Map<String, Object>> steps,
                                                            Map<String, Object> journeyMap,
                                                            Map<String, Object> resultMap) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (steps != null) data.put("steps", steps);
        if (journeyMap != null) data.put("journey", journeyMap);
        if (resultMap != null) data.put("result", resultMap);
        GetJourneyStateStatus typedStatus = "Completed".equals(status) ? GetJourneyStateStatus.COMPLETED
                : "InProgress".equals(status) ? GetJourneyStateStatus.IN_PROGRESS
                : "Failed".equals(status) ? GetJourneyStateStatus.FAILED
                : GetJourneyStateStatus.of(status);
        return GetJourneyStateResponseBody.builder()
                .instanceId("instance-1")
                .status(typedStatus)
                .data(data)
                .build();
    }

    private static GetJourneyStateResponseBody stateResponseWith(Map<String, Object> step) {
        return buildState("Completed", List.of(step), null, resultMap(null, "positive"));
    }

    @Test
    void theJourneyGraphsOwnTerminalDecisionNodeIsNotListedAsAModule() {
        Map<String, Object> realModule = stepMap("Document Authentication", "positive", "complete", null);
        // Go's own decision node: no name, no result — filtered by `step.name() != null`.
        Map<String, Object> decisionNode = stepMap(null, "positive", null, null);
        decisionNode.put("outcome", "Decision: Accept");

        RecordResponse record = mapper.toRecord(
                buildState("Completed", List.of(realModule, decisionNode), null, resultMap(null, "positive")), Map.of());

        assertThat(record.moduleRuns()).extracting("label").containsExactly("Document Authentication");
    }

    @Test
    void theModuleRunCarriesGosOwnDescriptiveOutcome() {
        Map<String, Object> step = stepMap("Document Classification", null, "complete", "Document Classified");

        RecordResponse record = mapper.toRecord(stateResponseWith(step), Map.of());

        assertThat(record.moduleRuns().get(0).outcome()).isEqualTo("Document Classified");
    }

    @Test
    void aConfirmedPositiveOutcomePhraseIsReportedAsPassEvenWithNoOutcomeClassification() {
        Map<String, Object> step = stepMap("Document Extraction", null, "complete", "Extraction Successful");

        RecordResponse record = mapper.toRecord(stateResponseWith(step), Map.of());

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.PASS);
    }

    @Test
    void anAmbiguousOutcomePhraseStaysReviewRatherThanBeingGuessedAtAsPositive() {
        Map<String, Object> noMatch = stepMap("Data Verification", null, "complete", "No Match");
        Map<String, Object> mediumRisk = stepMap("Document Authentication", null, "complete", "Medium Risk");

        RecordResponse record = mapper.toRecord(
                buildState("Completed", List.of(noMatch, mediumRisk), null, resultMap(null, "positive")), Map.of());

        assertThat(record.moduleRuns()).extracting("state")
                .containsExactly(ModuleState.REVIEW, ModuleState.REVIEW);
    }

    @Test
    void aModuleThatRanToCompletionButDeclinedIsReportedAsFailNotPass() {
        Map<String, Object> declined = stepMap("Document Authentication", "negative", "complete", null);

        RecordResponse record = mapper.toRecord(stateResponseWith(declined), Map.of());

        assertThat(record.moduleRuns()).hasSize(1);
        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.FAIL);
    }

    @Test
    void aModuleThatRanToCompletionAndPassedIsReportedAsPass() {
        Map<String, Object> passed = stepMap("Document Authentication", "positive", "complete", null);

        RecordResponse record = mapper.toRecord(stateResponseWith(passed), Map.of());

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.PASS);
    }

    @Test
    void aStillRunningModuleIsReportedAsRunningRegardlessOfAnyClassification() {
        Map<String, Object> running = stepMap("Facematch Verification", "negative", "pending", null);

        RecordResponse record = mapper.toRecord(stateResponseWith(running), Map.of());

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.RUNNING);
    }

    @Test
    void aModuleThatErroredIsReportedAsFail() {
        Map<String, Object> errored = stepMap("Document Classification", null, "error", null);

        RecordResponse record = mapper.toRecord(stateResponseWith(errored), Map.of());

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.FAIL);
    }

    @Test
    void aModuleWithNoResultYetFallsBackToItsOwnClassification() {
        Map<String, Object> notYetRun = stepMap("Liveness Verification", null, null, null);

        RecordResponse record = mapper.toRecord(stateResponseWith(notYetRun), Map.of());

        assertThat(record.moduleRuns().get(0).state()).isEqualTo(ModuleState.RUNNING);
    }

    // --- toRecord: journey name, reference, timestamps, total time, per-module timing and document type ---

    @Test
    void theRecordSummarisesTheJourneyWhenGoSuppliesTimingInfo() {
        Map<String, Object> step = stepMapWithTiming("Document Authentication", null, "complete", "Approved",
                "2026-08-26T09:41:02Z", "2026-08-26T09:41:08Z", 1600L);
        Map<String, Object> journey = journeyMap("UK retail account opening", "12", "2026-08-26T09:41:02Z", null);

        RecordResponse record = mapper.toRecord(
                buildState("Completed", List.of(step), journey, resultMap(null, "positive")), Map.of());

        assertThat(record.moduleRuns().get(0).ms()).isEqualTo("1.6s");
        assertThat(record.timing()).isEqualTo("6.0 seconds");
        assertThat(record.summary()).containsExactly(
                new SummaryRow("Journey", "UK retail account opening · v12"),
                new SummaryRow("Reference", "instance-1"),
                new SummaryRow("Started", "26 Aug 2026 09:41:02"),
                new SummaryRow("Decision reached", "26 Aug 2026 09:41:08"),
                new SummaryRow("Total time", "6.0 seconds"));
    }

    @Test
    void journeyInfoIsReadFromContextProcessWhenNotAtTheRoot() {
        // The live platform nests journey timing under context.process.journey,
        // not the root — same dual path as the raw client's allSteps()/journeyInfo().
        Map<String, Object> journey = journeyMap("Patient record access", null, "2026-08-26T09:41:02Z", "2026-08-26T09:41:04Z");
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("journey", journey);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("process", process);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", resultMap(null, "positive"));
        data.put("context", context);

        GetJourneyStateResponseBody body = GetJourneyStateResponseBody.builder()
                .instanceId("instance-2")
                .status(GetJourneyStateStatus.COMPLETED)
                .data(data)
                .build();

        RecordResponse record = mapper.toRecord(body, Map.of());

        assertThat(record.summary()).contains(new SummaryRow("Journey", "Patient record access"));
    }

    @Test
    void decisionReachedFallsBackToTheLatestStepsEndedAtWhenJourneyNeverSuppliesOne() {
        Map<String, Object> earlier = stepMapWithTiming("Document Classification", null, "complete", "Document Classified",
                "2026-08-26T09:41:00Z", "2026-08-26T09:41:38Z", 38000L);
        Map<String, Object> later = stepMapWithTiming("Facematch Verification", null, "complete", "Success",
                "2026-08-26T09:41:38Z", "2026-08-26T09:41:44Z", 6000L);
        Map<String, Object> journey = journeyMap("Patient record access", null, "2026-08-26T09:41:00Z", null);

        RecordResponse record = mapper.toRecord(
                buildState("Completed", List.of(earlier, later), journey, resultMap(null, "positive")), Map.of());

        assertThat(record.summary()).contains(
                new SummaryRow("Decision reached", "26 Aug 2026 09:41:44"),
                new SummaryRow("Total time", "44.0 seconds"));
    }

    @Test
    void theRecordHasNoTimingRowsWhenGoSuppliesNoJourneyTiming() {
        Map<String, Object> step = stepMap("Liveness Verification", "positive", "complete", null);

        RecordResponse record = mapper.toRecord(stateResponseWith(step), Map.of());

        assertThat(record.summary()).containsExactly(new SummaryRow("Reference", "instance-1"));
        assertThat(record.timing()).isEmpty();
        assertThat(record.moduleRuns().get(0).ms()).isNull();
    }

    @Test
    void theDocumentTypeIsReadFromDocumentClassificationsOwnStepResult() {
        // No typed context.subject set on this fixture, so this exercises the
        // fallback raw-`data`-walking path in SdkInteractionMapper.documentTypeLabel
        // — the same path the typed accessor falls back to on a live response
        // if the typed classification turns out not to carry a comparable field.
        Map<String, Object> classification = Map.of("name", "Utopia (UTO) GBG Sample Identification Card (2024)");
        Map<String, Object> document = Map.of("type", "primary", "classification", classification);
        Map<String, Object> subject = Map.of("documents", List.of(document));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "complete");
        result.put("outcome", "Document Classified");
        result.put("subject", subject);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", "Document Classification");
        step.put("result", result);

        RecordResponse record = mapper.toRecord(
                buildState("Completed", List.of(step), null, resultMap(null, "positive")), Map.of());

        assertThat(record.summary())
                .contains(new SummaryRow("Document", "Utopia (UTO) GBG Sample Identification Card (2024)"));
    }

    @Test
    void thereIsNoDocumentRowWhenGoNeverReportsAType() {
        Map<String, Object> step = stepMap("Liveness Verification", "positive", "complete", null);

        RecordResponse record = mapper.toRecord(stateResponseWith(step), Map.of());

        assertThat(record.summary()).noneMatch(row -> row.k().equals("Document"));
    }
}
