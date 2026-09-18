package com.gbg.samples.onboarding.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code application-meridian-health.yml}'s {@code screen-plan} block
 * actually binds into real content — the config replacing the old, compiled
 * {@code MeridianScreenPlan} class — not just that Spring starts without
 * throwing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("meridian-health")
class ScreenPlanPropertiesBindingTest {

    @Autowired
    private ScreenPlanProperties screenPlan;

    @Test
    void meridianHealthStagesBindInCollectionOrder() {
        // Consent first: a patient agrees to their record being opened before
        // anything is collected. Document before selfie, because Facematch
        // compares the selfie against the anchor image Classification
        // produces.
        assertThat(screenPlan.stages()).extracting(ScreenPlanProperties.Stage::name)
                .containsExactly("consent", "personal", "contact", "address", "document", "biometrics");

        ScreenPlanProperties.Stage document = screenPlan.stages().stream()
                .filter(s -> "document".equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(document.title()).isEqualTo("Scan your photo ID");
        assertThat(document.captureType()).isEqualTo("document");
        assertThat(document.accepted())
                .containsExactly("Passport", "Driving licence", "Biometric residence permit");
    }

    /**
     * A screen collecting several top-level elements names the extra ones in
     * also-prefixes; a single prefix would claim only the first, and the
     * screen would render one field out of three.
     */
    @Test
    void aMultiElementScreenClaimsEveryPrefixItLists() {
        ScreenPlanProperties.Stage personal = screenPlan.stages().stream()
                .filter(s -> "personal".equals(s.name()))
                .findFirst()
                .orElseThrow();

        assertThat(personal.prefixes())
                .containsExactly("MothersMaidenName", "Gender", "NationalInsuranceNumber");
        assertThat(personal.claimsRef("Gender")).isTrue();
        assertThat(personal.claimsRef("NationalInsuranceNumber")).isTrue();
        assertThat(personal.claimsRef("CurrentAddress/building")).isFalse();
    }

    @Test
    void consentChecksBindWithTheirDefaults() {
        assertThat(screenPlan.consentChecks()).hasSize(3);
        assertThat(screenPlan.consentChecks().get(0).name()).isEqualTo("shareWithClinicians");
        assertThat(screenPlan.consentChecks().get(0).defaultChecked()).isTrue();
        assertThat(screenPlan.consentChecks().get(1).defaultChecked()).isFalse();
    }
}
