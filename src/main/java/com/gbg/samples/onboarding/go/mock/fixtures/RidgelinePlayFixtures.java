package com.gbg.samples.onboarding.go.mock.fixtures;

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

import java.util.List;
import java.util.Map;

/**
 * Online gaming — player sign-up. Ported from apps/ridgeline-play/onboarding.config.ts.
 * The sign-up form is identical across scenarios — Go decides the branch
 * silently from the data submitted; there is no user-facing choice here, so
 * {@link com.gbg.samples.onboarding.go.mock.MockGoClient} picks the scenario
 * once per session the same way the front-end mock does.
 */
public final class RidgelinePlayFixtures {

    private RidgelinePlayFixtures() {
    }

    private static ScenarioStep signUp() {
        return ScenarioStep.builder()
                .kind(ScreenKind.FORM)
                .stage("Sign up")
                .title("Create your account")
                .body("You must be 18 or over to open an account.")
                .cta("Create account")
                .note("We check your age and identity from data in the background. Most players never scan a document.")
                .module("Age Verification").module("Data Verification").module("IP Geolocation").module("GBG Trust")
                .field(FieldSchema.of("email", "Email", "you@example.com").withType("email"))
                .field(FieldSchema.of("mobileNumber", "Mobile number", "+44").withType("tel"))
                .field(FieldSchema.of("dateOfBirth", "Date of birth", "DD / MM / YYYY").withType("date"))
                .field(FieldSchema.of("postcode", "Postcode", "M1 4BT").withType("postcode"))
                .build();
    }

    private static Scenario instant() {
        List<ScenarioStep> steps = List.of(
                signUp(),
                ScenarioStep.builder()
                        .kind(ScreenKind.PROCESSING).stage("Checks").title("Checking your details")
                        .moduleRun(new ModuleRun("Age Verification", ModuleState.PASS))
                        .moduleRun(new ModuleRun("Data Verification", ModuleState.PASS))
                        .moduleRun(new ModuleRun("IP Geolocation", ModuleState.PASS))
                        .moduleRun(new ModuleRun("GBG Trust", ModuleState.RUNNING))
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.CHOICE).stage("Limits").title("Set your deposit limit")
                        .body("Pick a weekly cap before you play. Lowering it takes effect at once; raising it takes 24 hours.")
                        .option(ScenarioOption.builder().value("50").label("£50 a week").detail("Our most common choice").icon("ph-shield-check").build())
                        .option(ScenarioOption.builder().value("250").label("£250 a week").detail("Change it any time in Account").icon("ph-sliders-horizontal").build())
                        .option(ScenarioOption.builder().value("none").label("No limit for now").detail("We still monitor play and will check in with you").icon("ph-warning").build())
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.RESULT).stage("Decision").title("You are in")
                        .decision(Decision.PASS).timing("Verified in 1.8 seconds")
                        .body("No documents needed. Your deposit limit is set and you can play now.")
                        .cta("Start playing")
                        .moduleRun(new ModuleRun("Age Verification", ModuleState.PASS, "0.3s"))
                        .moduleRun(new ModuleRun("Data Verification", ModuleState.PASS, "0.7s"))
                        .moduleRun(new ModuleRun("IP Geolocation", ModuleState.PASS, "0.2s"))
                        .moduleRun(new ModuleRun("GBG Trust", ModuleState.PASS, "0.6s"))
                        .moduleRun(new ModuleRun("Document Authentication", ModuleState.SKIPPED))
                        .recordNote("Your checks are logged for our licence conditions. Safer gambling tools and your limit history are in Account.")
                        .summary(List.of(
                                new SummaryRow("Journey", "GB player onboarding · v8"),
                                new SummaryRow("Player reference", "RP-4471902"),
                                new SummaryRow("Verified", "26 Aug 2026 21:12:01"),
                                new SummaryRow("Total time", "1.8 seconds"),
                                new SummaryRow("Modules run", "4 of 4"),
                                new SummaryRow("Age check", "18+ confirmed from data"),
                                new SummaryRow("Documents needed", "None"),
                                new SummaryRow("Jurisdiction", "Great Britain — licensed"),
                                new SummaryRow("Deposit limit", "£250 a week")
                        ))
                        .build()
        );
        return Scenario.builder().id("instant").label("Instant pass").steps(steps).build();
    }

    private static Scenario stepup() {
        List<ScenarioStep> steps = List.of(
                signUp(),
                ScenarioStep.builder()
                        .kind(ScreenKind.PROCESSING).stage("Checks").title("Checking your details")
                        .moduleRun(new ModuleRun("Age Verification", ModuleState.PASS))
                        .moduleRun(new ModuleRun("Data Verification", ModuleState.REVIEW))
                        .moduleRun(new ModuleRun("IP Geolocation", ModuleState.PASS))
                        .moduleRun(new ModuleRun("GBG Trust", ModuleState.PASS))
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.INTRO).stage("Step-up").title("One quick check")
                        .body("We could not confirm your age from data alone, so we need to see a document. About forty seconds.")
                        .note("This happens to roughly one player in eight, usually because they have recently moved.")
                        .cta("Scan my ID")
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.CAPTURE).captureType("document").stage("Document").title("Scan your photo ID")
                        .body("Passport or driving licence. Fill the frame and hold steady.")
                        .accepted("Passport").accepted("Driving licence")
                        .cta("Scan document")
                        .module("Document Classification").module("Document Authentication").module("Document Extraction")
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.CAPTURE).captureType("selfie").stage("Biometrics").title("Take a selfie")
                        .body("Last step. We check you match your document.")
                        .cta("Take selfie")
                        .module("Liveness Verification").module("Facematch Verification")
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.RESULT).stage("Decision").title("You are in")
                        .decision(Decision.PASS).timing("Verified in 54 seconds")
                        .body("Set a deposit limit before your first game. You can change it any time in Account.")
                        .cta("Set a deposit limit")
                        .moduleRun(new ModuleRun("Age Verification", ModuleState.PASS, "0.3s"))
                        .moduleRun(new ModuleRun("Data Verification", ModuleState.REVIEW, "0.8s"))
                        .moduleRun(new ModuleRun("Document Authentication", ModuleState.PASS, "2.2s"))
                        .moduleRun(new ModuleRun("Facematch Verification", ModuleState.PASS, "1.4s"))
                        .recordNote("You will not be asked for a document again unless your details change.")
                        .summary(List.of(
                                new SummaryRow("Journey", "GB player onboarding · v8"),
                                new SummaryRow("Player reference", "RP-4471938"),
                                new SummaryRow("Verified", "26 Aug 2026 21:44:30"),
                                new SummaryRow("Total time", "54 seconds"),
                                new SummaryRow("Modules run", "9 of 9"),
                                new SummaryRow("Why we stepped up", "Address on file under 6 months old"),
                                new SummaryRow("Document", "UK driving licence"),
                                new SummaryRow("Jurisdiction", "Great Britain — licensed"),
                                new SummaryRow("Outcome", "Verified — no further checks")
                        ))
                        .build()
        );
        return Scenario.builder().id("stepup").label("Step-up to document").steps(steps).build();
    }

    private static Scenario blocked() {
        List<ScenarioStep> steps = List.of(
                signUp(),
                ScenarioStep.builder()
                        .kind(ScreenKind.PROCESSING).stage("Checks").title("Checking your details")
                        .moduleRun(new ModuleRun("Age Verification", ModuleState.PASS))
                        .moduleRun(new ModuleRun("IP Geolocation", ModuleState.FAIL))
                        .moduleRun(new ModuleRun("Data Verification", ModuleState.SKIPPED))
                        .moduleRun(new ModuleRun("GBG Trust", ModuleState.SKIPPED))
                        .build(),
                ScenarioStep.builder()
                        .kind(ScreenKind.RESULT).stage("Decision").title("We cannot open an account")
                        .decision(Decision.FAIL).timing("Declined in 1.2 seconds")
                        .body("Ridgeline Play is licensed in Great Britain only, and your connection appears to be outside that area. If you are travelling, try again when you are back.")
                        .cta("Read our licence terms")
                        .moduleRun(new ModuleRun("IP Geolocation", ModuleState.FAIL, "0.2s"))
                        .moduleRun(new ModuleRun("Age Verification", ModuleState.PASS, "0.3s"))
                        .moduleRun(new ModuleRun("Data Verification", ModuleState.SKIPPED))
                        .recordNote("No account was created and no identity documents were collected. Ask us to delete the attempt log at any time.")
                        .summary(List.of(
                                new SummaryRow("Journey", "GB player onboarding · v8"),
                                new SummaryRow("Attempt reference", "RP-4471955"),
                                new SummaryRow("Declined", "26 Aug 2026 22:03:09"),
                                new SummaryRow("Total time", "1.2 seconds"),
                                new SummaryRow("Modules run", "2 of 4"),
                                new SummaryRow("Declined by", "IP Geolocation — outside licensed area"),
                                new SummaryRow("Age check", "18+ confirmed"),
                                new SummaryRow("Data retained", "Email and attempt log only")
                        ))
                        .build()
        );
        return Scenario.builder().id("blocked").label("Blocked — jurisdiction").steps(steps).build();
    }

    public static MarketFixtures build() {
        return MarketFixtures.builder()
                .defaultScenarioId("instant")
                .scenarios(Map.of(
                        "instant", instant(),
                        "stepup", stepup(),
                        "blocked", blocked()
                ))
                .build();
    }
}
