package com.gbg.samples.onboarding.go.mock.fixtures;

import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.FieldSchema;
import com.gbg.samples.onboarding.api.dto.ModuleRun;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import com.gbg.samples.onboarding.go.mock.MarketFixtures;
import com.gbg.samples.onboarding.go.mock.Scenario;
import com.gbg.samples.onboarding.go.mock.ScenarioStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Banking — current account opening. Ported from apps/northbank/onboarding.config.ts. */
public final class NorthbankFixtures {

    private NorthbankFixtures() {
    }

    private static List<ScenarioStep> prefix() {
        List<ScenarioStep> steps = new ArrayList<>();
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.INTRO)
                .stage("Start")
                .title("Open your Northbank current account")
                .body("Four steps, about four minutes. You will need photo ID and three years of address history.")
                .note("Northbank checks your identity with GBG. Your document images are not kept on this device.")
                .cta("Get started")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.FORM)
                .stage("Details")
                .eyebrow("Step 1 of 4")
                .title("Your details")
                .body("We check these against trusted consumer and government data sources. No credit footprint.")
                .cta("Continue")
                .module("Data Verification")
                .field(FieldSchema.of("fullName", "Full name", "Amara Osei"))
                .field(FieldSchema.of("dateOfBirth", "Date of birth", "DD / MM / YYYY").withType("date"))
                .field(FieldSchema.of("homeAddress", "Home address", "Start typing your postcode", "We will ask for earlier addresses next"))
                .field(FieldSchema.of("mobileNumber", "Mobile number", "+44").withType("tel"))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CAPTURE)
                .captureType("document")
                .stage("Document")
                .eyebrow("Step 2 of 4")
                .title("Scan your photo ID")
                .body("Hold the document flat and fill the frame. Where your document has a chip, we read it for a stronger result.")
                .accepted("Passport").accepted("UK driving licence").accepted("National ID card")
                .cta("Scan document")
                .secondaryCta("Use a digital ID instead")
                .module("Document Classification").module("Document Authentication").module("Document Extraction").module("NFC Chip Authentication")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CAPTURE)
                .captureType("selfie")
                .stage("Biometrics")
                .eyebrow("Step 3 of 4")
                .title("Take a selfie")
                .body("We confirm a real person is present, then compare your face with the photo on your document.")
                .cta("Take selfie")
                .module("Liveness Verification").module("Facematch Verification")
                .build());
        return steps;
    }

    private static List<ModuleRun> bankRuns(ModuleState dataVerificationState) {
        return List.of(
                new ModuleRun("Data Verification", dataVerificationState, "0.9s"),
                new ModuleRun("Document Authentication", ModuleState.PASS, "2.1s"),
                new ModuleRun("Facematch Verification", ModuleState.PASS, "1.4s"),
                new ModuleRun("PEPs and Sanctions", ModuleState.PASS, "1.2s"),
                new ModuleRun("Financial Screening", ModuleState.PASS, "0.8s")
        );
    }

    private static Scenario straightThrough() {
        List<ScenarioStep> steps = prefix();
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.PROCESSING)
                .stage("Screening")
                .eyebrow("Step 4 of 4")
                .title("Running your checks")
                .body("This usually takes a few seconds.")
                .module("PEPs and Sanctions").module("Financial Screening").module("GBG Trust")
                .moduleRun(new ModuleRun("Data Verification", ModuleState.PASS))
                .moduleRun(new ModuleRun("Document Authentication", ModuleState.PASS))
                .moduleRun(new ModuleRun("PEPs and Sanctions", ModuleState.RUNNING))
                .moduleRun(new ModuleRun("Financial Screening", ModuleState.RUNNING))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("Account opened")
                .decision(Decision.PASS)
                .timing("Decision reached in 6 seconds")
                .body("Your account number and sort code are in the Northbank app. Your card arrives within five working days.")
                .moduleRuns(bankRuns(ModuleState.PASS))
                .cta("Go to my account")
                .recordNote("Download your verification record as a PDF, or ask us for it later. We keep it for six years under our AML duties.")
                .summary(List.of(
                        new SummaryRow("Journey", "UK retail account opening · v12"),
                        new SummaryRow("Reference", "NB-2026-004182"),
                        new SummaryRow("Started", "26 Aug 2026 09:41:02"),
                        new SummaryRow("Decision reached", "26 Aug 2026 09:41:08"),
                        new SummaryRow("Total time", "6.4 seconds"),
                        new SummaryRow("Modules run", "10 of 10"),
                        new SummaryRow("Document", "UK passport · chip read"),
                        new SummaryRow("Data sources matched", "3 of 3"),
                        new SummaryRow("Outcome", "Approved — no manual review")
                ))
                .build());
        return Scenario.builder().id("straight").label("Straight-through").steps(steps).build();
    }

    /** Shared up to and including the proof-of-address upload — 'refer' and 'denied' only differ in how the manual review resolves. */
    private static List<ScenarioStep> referPrefix() {
        List<ScenarioStep> steps = prefix();
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.PROCESSING)
                .stage("Screening")
                .eyebrow("Step 4 of 4")
                .title("Running your checks")
                .body("This usually takes a few seconds.")
                .module("PEPs and Sanctions").module("Financial Screening").module("GBG Trust")
                .moduleRun(new ModuleRun("Data Verification", ModuleState.REVIEW))
                .moduleRun(new ModuleRun("Document Authentication", ModuleState.PASS))
                .moduleRun(new ModuleRun("PEPs and Sanctions", ModuleState.PASS))
                .moduleRun(new ModuleRun("Financial Screening", ModuleState.RUNNING))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("We need one more document")
                .decision(Decision.REFER)
                .timing("Referred after 6 seconds")
                .body("Your address did not match our data sources. Add a bank statement or utility bill dated in the last three months.")
                .moduleRuns(bankRuns(ModuleState.REVIEW))
                .cta("Add a document")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.UPLOAD)
                .stage("Proof of address")
                .title("Proof of address")
                .body("We read the name and address from your document and compare them with what you told us.")
                .accepted("Bank statement — last 3 months").accepted("Utility bill").accepted("Council tax letter")
                .cta("Submit")
                .module("Proof of Address Extraction").module("Document Attachments")
                .build());
        return steps;
    }

    private static Scenario referral() {
        List<ScenarioStep> steps = referPrefix();
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("With our team")
                .decision(Decision.REFER)
                .timing("Most reviews close within two hours")
                .body("An analyst is checking your document. We will email a.osei@example.com as soon as it is done.")
                .moduleRun(new ModuleRun("Proof of Address Extraction", ModuleState.PASS, "1.6s"))
                .moduleRun(new ModuleRun("Manual review", ModuleState.RUNNING))
                .cta("Done")
                .recordNote("You can add another document while the review is open. We will email you either way.")
                .summary(List.of(
                        new SummaryRow("Journey", "UK retail account opening · v12"),
                        new SummaryRow("Reference", "NB-2026-004219"),
                        new SummaryRow("Started", "26 Aug 2026 14:02:11"),
                        new SummaryRow("Referred", "26 Aug 2026 14:02:17"),
                        new SummaryRow("Time to referral", "6.1 seconds"),
                        new SummaryRow("Modules run", "11 of 12"),
                        new SummaryRow("Referred by", "Data Verification — address not matched"),
                        new SummaryRow("Evidence added", "1 document · proof of address"),
                        new SummaryRow("With", "Northbank onboarding team")
                ))
                .build());
        return Scenario.builder().id("refer").label("Referred — address mismatch").steps(steps).build();
    }

    /**
     * Terminal state once a reviewer denies the referral (Manual Review
     * module, the Deny outcome) — distinct from 'refer' above, which just
     * means the review is still open.
     */
    private static Scenario denied() {
        List<ScenarioStep> steps = referPrefix();
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("We could not open your account")
                .decision(Decision.FAIL)
                .timing("Decision reached after review")
                .body("After reviewing your documents, we are unable to open a Northbank account for you at this time. This does not affect your credit score. If you think this is a mistake, call us on 0800 000 000.")
                .moduleRun(new ModuleRun("Proof of Address Extraction", ModuleState.PASS, "1.6s"))
                .moduleRun(new ModuleRun("Manual review", ModuleState.FAIL))
                .cta("Contact us")
                .recordNote("You can ask for a copy of this decision, or find out how to appeal it. We keep a record of this application for our regulatory duties.")
                .summary(List.of(
                        new SummaryRow("Journey", "UK retail account opening · v12"),
                        new SummaryRow("Reference", "NB-2026-004233"),
                        new SummaryRow("Started", "27 Aug 2026 10:15:40"),
                        new SummaryRow("Referred", "27 Aug 2026 10:15:46"),
                        new SummaryRow("Reviewed", "27 Aug 2026 15:02:11"),
                        new SummaryRow("Modules run", "12 of 12"),
                        new SummaryRow("Declined by", "Manual review — proof of address not accepted"),
                        new SummaryRow("Evidence added", "1 document · proof of address"),
                        new SummaryRow("With", "Northbank onboarding team"),
                        new SummaryRow("Outcome", "Declined after manual review")
                ))
                .build());
        return Scenario.builder().id("denied").label("Referred — denied on review").steps(steps).build();
    }

    public static MarketFixtures build() {
        return MarketFixtures.builder()
                .defaultScenarioId("straight")
                .scenarios(Map.of(
                        "straight", straightThrough(),
                        "refer", referral(),
                        "denied", denied()
                ))
                .build();
    }
}
