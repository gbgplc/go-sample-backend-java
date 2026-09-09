package com.gbg.samples.onboarding.config;

import com.gbg.samples.onboarding.api.dto.ConsentCheck;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The live-mode screen plan for this deployment's market: which domain
 * elements map to which screen, in what order, and the copy that goes with
 * each one. Lives in config ({@code application-<market>.yml}) rather than
 * in {@code DefaultInteractionMapper} so a market's live-mode presentation —
 * or a new market's, once it has a published journey — can be written or
 * edited without touching Java.
 *
 * Empty by default: a market with no published journey (see HANDOFF.md,
 * section 7) has nothing to configure here yet, and {@code DefaultInteractionMapper}
 * treats an empty plan the same as one that doesn't recognise what Go sent —
 * a generic fallback form, not a crash.
 *
 * {@code @Validated} so a stage that's missing a required field — a config
 * typo in hand-written YAML, exactly the kind {@code Stage.prefix} used to
 * catch at compile time back when this was the hardcoded
 * {@code MeridianScreenPlan} class — fails application startup with a clear
 * binding error, rather than an NPE the first time a request happens to
 * reach that stage.
 */
@ConfigurationProperties(prefix = "screen-plan")
@Validated
public record ScreenPlanProperties(@Valid List<Stage> stages, @Valid List<ConsentCheck> consentChecks) {

    public List<Stage> stages() {
        return stages == null ? List.of() : stages;
    }

    public List<ConsentCheck> consentChecks() {
        return consentChecks == null ? List.of() : consentChecks;
    }

    /** One screen: which outstanding elements it satisfies, and the copy that goes with it. */
    public record Stage(
            @NotBlank String name,
            @NotNull ScreenKind kind,
            @NotBlank String prefix,
            @NotBlank String stage,
            @NotBlank String title,
            @NotBlank String body,
            String cta,
            String captureType,
            List<String> accepted,
            List<String> modules
    ) {
        /** True when this stage still has something to collect. */
        public boolean claims(List<String> outstanding) {
            return outstanding.stream().anyMatch(o -> o.startsWith(prefix));
        }
    }
}
