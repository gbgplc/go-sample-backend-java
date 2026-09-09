package com.gbg.samples.onboarding.config;

import com.gbg.samples.onboarding.api.dto.ScreenKind;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stage missing a required field used to be caught at compile time back
 * when this was the hardcoded {@code MeridianScreenPlan} class; now it's
 * hand-written YAML, so this pins that the same mistake fails Bean
 * Validation (and, in the running app, application startup) instead of
 * NPE-ing the first time a request reaches that stage.
 */
class ScreenPlanPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aStageMissingItsPrefixFailsValidation() {
        ScreenPlanProperties.Stage stage = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, null, "Document",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                null, null);

        Set<ConstraintViolation<ScreenPlanProperties.Stage>> violations = validator.validate(stage);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("prefix"));
    }

    @Test
    void aStageMissingItsStageLabelFailsValidation() {
        ScreenPlanProperties.Stage stage = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, "PrimaryDocument/", " ",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                null, null);

        Set<ConstraintViolation<ScreenPlanProperties.Stage>> violations = validator.validate(stage);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("stage"));
    }

    @Test
    void aFullyPopulatedStagePassesValidation() {
        ScreenPlanProperties.Stage stage = new ScreenPlanProperties.Stage(
                "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
                "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
                null, null);

        assertThat(validator.validate(stage)).isEmpty();
    }
}
