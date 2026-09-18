package com.gbg.samples.onboarding.go.mock.fixtures;

import com.gbg.samples.onboarding.api.dto.ConsentCheck;
import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.FieldSchema;
import com.gbg.samples.onboarding.api.dto.ModuleRun;
import com.gbg.samples.onboarding.api.dto.ModuleState;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import com.gbg.samples.onboarding.go.mock.MarketFixtures;
import com.gbg.samples.onboarding.go.mock.Scenario;
import com.gbg.samples.onboarding.go.mock.ScenarioOption;
import com.gbg.samples.onboarding.go.mock.ScenarioStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Healthcare — patient record access. Ported from apps/meridian-health/onboarding.config.ts. */
public final class MeridianHealthFixtures {

    private MeridianHealthFixtures() {
    }

    /**
     * Shared content for every scenario; only the "Someone I care for"
     * branch target changes, so a scenario reached directly via
     * {@code ?mock_scenario=} (rather than by picking through the choice)
     * doesn't get bounced back to a sibling scenario if the choice screen is
     * re-answered — MockGoClient only switches scenario when the option's
     * branchTo differs from the session's current one.
     */
    private static List<ScenarioStep> prefix(String carerBranch) {
        List<ScenarioStep> steps = new ArrayList<>();
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.INTRO)
                .stage("Start")
                .title("Verify your identity to see your health record")
                .body("Meridian confirms who you are before showing your record. About three minutes.")
                .note("You can also bring ID to reception and verify in person.")
                .cta("Start")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CHOICE)
                .stage("Who")
                .title("Who are you registering?")
                .body("Both routes ask for the same identity checks. Acting for someone else adds one document.")
                .option(ScenarioOption.builder().value("self").label("Myself").detail("You are the patient").icon("ph-user").branchTo("self").build())
                .option(ScenarioOption.builder().value("carer").label("Someone I care for").detail("You hold parental responsibility or a lasting power of attorney").icon("ph-users-three").branchTo(carerBranch).build())
                .build());
        return steps;
    }

    private static Scenario self() {
        List<ScenarioStep> steps = prefix("carer");
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.FORM)
                .stage("Details")
                .title("Your details")
                .body("We match these against national records to find your patient file.")
                .cta("Continue")
                .module("Data Verification")
                .field(FieldSchema.of("fullName", "Full name", "Priya Raman"))
                .field(FieldSchema.of("dateOfBirth", "Date of birth", "DD / MM / YYYY").withType("date"))
                .field(FieldSchema.of("postcode", "Postcode", "SE1 7TP").withType("postcode"))
                .field(FieldSchema.of("nhsNumber", "NHS number", "000 000 0000", "Optional. Ten digits, on your medical card."))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CHOICE)
                .stage("Route")
                .title("How would you like to verify?")
                .body("A digital ID you already hold is quickest. Scanning a document works if you have none.")
                .module("Digital Identity Insights")
                .option(ScenarioOption.builder().value("digital").label("Use a digital ID").detail("Post Office EasyID, Yoti, or a bank ID you already hold").icon("ph-identification-badge").build())
                .option(ScenarioOption.builder().value("document").label("Scan a photo ID").detail("Passport, driving licence or biometric residence permit").icon("ph-scan").build())
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CAPTURE).captureType("document")
                .stage("Document")
                .title("Scan your photo ID")
                .body("We check the document is genuine and read the details from it.")
                .accepted("Passport").accepted("Driving licence").accepted("Biometric residence permit")
                .cta("Scan document")
                .module("Document Authentication").module("Document Extraction")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CAPTURE).captureType("selfie")
                .stage("Biometrics")
                .title("Take a selfie")
                .body("This proves you are the person in the document, so nobody else can open your record.")
                .cta("Take selfie")
                .module("Liveness Verification").module("Facematch Verification")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CONSENT)
                .stage("Consent")
                .title("Share your record with Meridian Health")
                .body("You decide what each group can see. You can change any of this later in settings.")
                .cta("Agree and continue")
                .check(new ConsentCheck("shareWithClinicians", "Show my record to clinicians treating me", "GP notes, test results, prescriptions and referrals", true))
                .check(ConsentCheck.of("sharePrescriptions", "Share prescriptions with my chosen pharmacy", "Only the pharmacy you name"))
                .check(ConsentCheck.of("useForResearch", "Use my data for research", "Anonymised, optional, and unrelated to your care"))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("Record linked")
                .decision(Decision.PASS)
                .timing("Verified in 9 seconds")
                .body("You can now see appointments, test results and prescriptions in your Meridian account.")
                .cta("Open my record")
                .moduleRun(new ModuleRun("Data Verification", ModuleState.PASS, "1.1s"))
                .moduleRun(new ModuleRun("Document Authentication", ModuleState.PASS, "2.4s"))
                .moduleRun(new ModuleRun("Facematch Verification", ModuleState.PASS, "1.3s"))
                .moduleRun(new ModuleRun("Liveness Verification", ModuleState.PASS, "2.9s"))
                .recordNote("Your identity evidence is held separately from your medical record. Ask the practice for a copy, or to delete it once registration is complete.")
                .summary(List.of(
                        new SummaryRow("Journey", "Patient record access · v3"),
                        new SummaryRow("Reference", "MH-VER-77341"),
                        new SummaryRow("Verified", "26 Aug 2026 08:17:44"),
                        new SummaryRow("Total time", "9.2 seconds"),
                        new SummaryRow("Modules run", "6 of 6"),
                        new SummaryRow("Identity route", "Photo ID and selfie"),
                        new SummaryRow("Record matched", "1 patient file"),
                        new SummaryRow("Consent given", "Clinicians and named pharmacy"),
                        new SummaryRow("Assurance level", "Medium — meets practice policy")
                ))
                .build());
        return Scenario.builder().id("self").label("Patient — verified in app").steps(steps).build();
    }

    /** Shared up to and including the authority-document upload — 'carer' and 'carer-denied' only differ in how the clinician's review resolves. */
    private static List<ScenarioStep> carerPrefix(String carerBranch) {
        List<ScenarioStep> steps = prefix(carerBranch);
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.FORM)
                .stage("Patient")
                .title("Who are you acting for?")
                .body("We find their record first, then verify you.")
                .cta("Continue")
                .module("Data Verification")
                .field(FieldSchema.of("patientFullName", "Patient's full name", "Margaret Ellis"))
                .field(FieldSchema.of("patientDateOfBirth", "Patient's date of birth", "DD / MM / YYYY").withType("date"))
                .field(FieldSchema.of("patientPostcode", "Patient's postcode", "SE1 7TP").withType("postcode"))
                .field(FieldSchema.of("relationship", "Your relationship to them", "Attorney, parent, guardian"))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.FORM)
                .stage("Details")
                .title("Your details")
                .body("Now the identity checks for you, the carer.")
                .cta("Continue")
                .module("Data Verification")
                .field(FieldSchema.of("fullName", "Full name", "Daniel Ellis"))
                .field(FieldSchema.of("dateOfBirth", "Date of birth", "DD / MM / YYYY").withType("date"))
                .field(FieldSchema.of("homeAddress", "Home address", "Start typing your postcode"))
                .field(FieldSchema.of("mobileNumber", "Mobile number", "+44").withType("tel"))
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CAPTURE).captureType("document")
                .stage("Document")
                .title("Scan your photo ID")
                .body("We check the document is genuine and read the details from it.")
                .accepted("Passport").accepted("Driving licence").accepted("Biometric residence permit")
                .cta("Scan document")
                .module("Document Authentication").module("Document Extraction")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.CAPTURE).captureType("selfie")
                .stage("Biometrics")
                .title("Take a selfie")
                .body("This proves you are the person in the document.")
                .cta("Take selfie")
                .module("Liveness Verification").module("Facematch Verification")
                .build());
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.UPLOAD)
                .stage("Authority")
                .title("Proof you can act for Margaret Ellis")
                .body("A clinician reviews this before the record is shared. It is the one step we cannot automate.")
                .accepted("Lasting power of attorney").accepted("Court order").accepted("Clinic authorisation letter")
                .cta("Submit")
                .module("Document Attachments")
                .build());
        return steps;
    }

    private static Scenario carer() {
        List<ScenarioStep> steps = carerPrefix("carer");
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("With the practice team")
                .decision(Decision.REFER)
                .timing("Usually within one working day")
                .body("Your identity is verified. A clinician is checking your authority to act for Margaret, and we will email you when it is approved.")
                .cta("Done")
                .moduleRun(new ModuleRun("Data Verification", ModuleState.PASS, "1.0s"))
                .moduleRun(new ModuleRun("Facematch Verification", ModuleState.PASS, "1.3s"))
                .moduleRun(new ModuleRun("Document Attachments", ModuleState.REVIEW))
                .recordNote("Nothing from Margaret's record is shared until a clinician approves your authority.")
                .summary(List.of(
                        new SummaryRow("Journey", "Patient record access · v3"),
                        new SummaryRow("Reference", "MH-VER-77358"),
                        new SummaryRow("Acting for", "Margaret Ellis · b. 1948"),
                        new SummaryRow("Your identity", "Verified in 8.6 seconds"),
                        new SummaryRow("Modules run", "6 of 6"),
                        new SummaryRow("Authority document", "Lasting power of attorney"),
                        new SummaryRow("Awaiting", "Clinician review of authority"),
                        new SummaryRow("Outcome so far", "Identity verified, access pending")
                ))
                .build());
        return Scenario.builder().id("carer").label("Carer acting for a patient").steps(steps).build();
    }

    /**
     * Terminal state once a clinician denies the authority-to-act review
     * (Manual Review module, the Deny outcome) — distinct from 'carer'
     * above, which just means the review is still open.
     */
    private static Scenario carerDenied() {
        List<ScenarioStep> steps = carerPrefix("carer-denied");
        steps.add(ScenarioStep.builder()
                .kind(ScreenKind.RESULT)
                .stage("Decision")
                .title("We could not verify your authority")
                .decision(Decision.FAIL)
                .timing("Decision reached after review")
                .body("A clinician reviewed the document you provided and could not confirm you're authorised to act for Margaret Ellis. Nothing from her record has been shared. Contact the practice with updated documentation if you believe this is wrong.")
                .cta("Contact the practice")
                .moduleRun(new ModuleRun("Data Verification", ModuleState.PASS, "1.0s"))
                .moduleRun(new ModuleRun("Facematch Verification", ModuleState.PASS, "1.3s"))
                .moduleRun(new ModuleRun("Document Attachments", ModuleState.FAIL))
                .recordNote("Your own identity remains verified. You can submit a new authority document at any time.")
                .summary(List.of(
                        new SummaryRow("Journey", "Patient record access · v3"),
                        new SummaryRow("Reference", "MH-VER-77372"),
                        new SummaryRow("Acting for", "Margaret Ellis · b. 1948"),
                        new SummaryRow("Your identity", "Verified in 8.4 seconds"),
                        new SummaryRow("Modules run", "6 of 6"),
                        new SummaryRow("Authority document", "Lasting power of attorney — not accepted"),
                        new SummaryRow("Reviewed by", "Clinician"),
                        new SummaryRow("Outcome", "Access denied — authority not confirmed")
                ))
                .build());
        return Scenario.builder().id("carer-denied").label("Carer — authority denied on review").steps(steps).build();
    }

    public static MarketFixtures build() {
        return MarketFixtures.builder()
                .defaultScenarioId("self")
                .scenarios(Map.of(
                        "self", self(),
                        "carer", carer(),
                        "carer-denied", carerDenied()
                ))
                .build();
    }
}
