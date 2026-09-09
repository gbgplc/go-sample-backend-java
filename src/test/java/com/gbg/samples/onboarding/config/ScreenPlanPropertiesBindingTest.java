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
        assertThat(screenPlan.stages()).extracting(ScreenPlanProperties.Stage::name)
                .containsExactly("document", "biometrics", "consent");
        assertThat(screenPlan.stages().get(0).title()).isEqualTo("Scan your photo ID");
        assertThat(screenPlan.stages().get(0).captureType()).isEqualTo("document");
        assertThat(screenPlan.stages().get(0).accepted())
                .containsExactly("Passport", "Driving licence", "Biometric residence permit");
    }

    @Test
    void consentChecksBindWithTheirDefaults() {
        assertThat(screenPlan.consentChecks()).hasSize(3);
        assertThat(screenPlan.consentChecks().get(0).name()).isEqualTo("shareWithClinicians");
        assertThat(screenPlan.consentChecks().get(0).defaultChecked()).isTrue();
        assertThat(screenPlan.consentChecks().get(1).defaultChecked()).isFalse();
    }
}
