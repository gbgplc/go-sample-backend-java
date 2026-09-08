package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.dto.ConsentCheck;
import com.gbg.samples.onboarding.api.dto.FieldSchema;
import com.gbg.samples.onboarding.api.dto.ScreenKind;

import java.util.List;
import java.util.Map;

/**
 * Turns Go's flat list of outstanding domain elements into the sequence of
 * screens the Meridian Health design calls for.
 *
 * <h2>Why this class exists</h2>
 * Go does not return screens. A fetch against the published journey returns
 * <em>one</em> interaction ({@code segment1}) listing every element still
 * outstanding, all at once:
 *
 * <pre>
 *   PrimaryDocument/side1Image
 *   Selfie/selfieImage
 *   Consent/url
 * </pre>
 *
 * How those become screens is entirely the client's decision — that is what
 * {@code delivery: "api"} buys, and it is also the work it costs. This class
 * is that decision, expressed as an ordered plan: the first stage whose
 * elements are still outstanding is the screen to render next.
 *
 * <h2>Ordering</h2>
 * Stage order follows the design and the module chain: document before selfie
 * (Facematch compares the selfie against the anchor image that Document
 * Classification produces), consent last. Go enforces the module dependency,
 * not the collection order, but collecting in module order keeps the progress
 * rail honest.
 *
 * <h2>Scope</h2>
 * Written against the patient self-registration journey published on
 * {@code gbggo4-demo} (resource {@code b3d1495…@2g6no1nz}), verified by live
 * fetch on 2026-09-07. Two things the design has that the journey does not:
 *
 * <ul>
 *   <li><b>No identity form.</b> The journey has no {@code FullName} or
 *       {@code DateOfBirth} element — Data Verification was removed after it
 *       proved unable to source them on this tenant. Restore the DETAILS stage
 *       below when that module works and those elements reappear.</li>
 *   <li><b>No carer branch.</b> Self-registration only. The carer route
 *       verifies two people, which Go's single-subject model does not express
 *       directly; that is an open design question, not an omission here.</li>
 * </ul>
 */
final class MeridianScreenPlan {

    private MeridianScreenPlan() {
    }

    /** One screen: which elements it satisfies, and the copy that goes with it. */
    record Stage(
            String name,
            ScreenKind kind,
            String prefix,
            String stage,
            String title,
            String body,
            String cta,
            String captureType,
            List<String> accepted,
            List<String> modules
    ) {
        /** True when this stage still has something to collect. */
        boolean claims(List<String> outstanding) {
            return outstanding.stream().anyMatch(o -> o.startsWith(prefix));
        }
    }

    /**
     * The stages, in collection order. Copy is lifted from the Meridian design
     * ({@code apps/meridian-health/onboarding.config.ts}) so the live journey
     * reads identically to the mock.
     */
    static final List<Stage> STAGES = List.of(
            new Stage("document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
                    "Scan your photo ID",
                    "We check the document is genuine and read the details from it.",
                    "Scan document", "document",
                    List.of("Passport", "Driving licence", "Biometric residence permit"),
                    List.of("Document Classification", "Document Extraction", "Document Authentication")),

            new Stage("biometrics", ScreenKind.CAPTURE, "Selfie/", "Biometrics",
                    "Take a selfie",
                    "This proves you are the person in the document, so nobody else can open your record.",
                    "Take selfie", "selfie",
                    null,
                    List.of("Liveness Verification", "Facematch Verification")),

            new Stage("consent", ScreenKind.CONSENT, "Consent/", "Consent",
                    "Share your record with Meridian Health",
                    "You decide what each group can see. You can change any of this later in settings.",
                    "Agree and continue", null,
                    null,
                    List.of("Consent Collection"))
    );

    /** The next screen to render, or empty when nothing is outstanding. */
    static java.util.Optional<Stage> next(List<String> outstanding) {
        if (outstanding == null || outstanding.isEmpty()) return java.util.Optional.empty();
        return STAGES.stream().filter(s -> s.claims(outstanding)).findFirst();
    }

    /** The rail: every stage, with the current one active and earlier ones done. */
    static List<String> stageLabels() {
        return STAGES.stream().map(Stage::stage).toList();
    }

    /**
     * The consent checks shown on the consent screen.
     *
     * The design has three independent toggles; the Consent Collection module
     * records acceptance of <em>one</em> agreement (its only required element
     * is {@code Consent/url}). Only the first gates access — a patient who
     * will not share with clinicians cannot be given a record — so that one
     * drives the module, and the other two are recorded as preferences
     * alongside it rather than as separate consent records.
     */
    static List<ConsentCheck> consentChecks() {
        return List.of(
                new ConsentCheck("shareWithClinicians",
                        "Show my record to clinicians treating me",
                        "GP notes, test results, prescriptions and referrals", true),
                new ConsentCheck("sharePrescriptions",
                        "Share prescriptions with my chosen pharmacy",
                        "Only the pharmacy you name", false),
                new ConsentCheck("useForResearch",
                        "Use my data for research",
                        "Anonymised, optional, and unrelated to your care", false)
        );
    }

    /**
     * Fields for a form stage, derived from what Go says is outstanding.
     * Unused while the journey collects no typed identity data, but kept so
     * the DETAILS stage can be restored without rewriting this class.
     */
    static List<FieldSchema> fieldsFor(Stage stage, List<String> outstanding) {
        return outstanding.stream()
                .filter(o -> o.startsWith(stage.prefix()))
                .map(o -> FieldSchema.of(o, label(o), null))
                .toList();
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("FullName/firstName", "First name"),
            Map.entry("FullName/lastNames", "Last name"),
            Map.entry("DateOfBirth", "Date of birth"),
            Map.entry("CurrentAddress/building", "Building name or number"),
            Map.entry("CurrentAddress/thoroughfare", "Street"),
            Map.entry("CurrentAddress/locality", "Town or city"),
            Map.entry("CurrentAddress/postalCode", "Postcode"),
            Map.entry("CurrentAddress/country", "Country"),
            Map.entry("MobilePhone/number", "Mobile number")
    );

    /** A human label for a domain element ref, falling back to a de-camel-cased leaf. */
    static String label(String ref) {
        String known = LABELS.get(ref);
        if (known != null) return known;
        String leaf = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
        String spaced = leaf.replaceAll("(?<!^)(?=[A-Z])", " ").toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
