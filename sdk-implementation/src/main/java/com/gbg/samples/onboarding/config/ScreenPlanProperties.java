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
            List<String> modules,
            Boolean alwaysCollect,
            List<String> alsoPrefixes
    ) {
        /**
         * Every ref prefix this screen collects: {@code prefix} plus any
         * {@code also-prefixes}.
         *
         * One screen often collects several domain elements — this journey's
         * personal-details page asks for MothersMaidenName, Gender and
         * NationalInsuranceNumber, and its contact page for two emails and two
         * phones. Each is a separate top-level element, so a single prefix
         * claims only the first and the screen renders one box out of three.
         */
        public List<String> prefixes() {
            if (alsoPrefixes == null || alsoPrefixes.isEmpty()) return List.of(prefix);
            List<String> all = new java.util.ArrayList<>(alsoPrefixes.size() + 1);
            all.add(prefix);
            all.addAll(alsoPrefixes);
            return List.copyOf(all);
        }

        /**
         * This stage rendered as the second document side.
         *
         * A two-sided document is one stage collected twice: Go accepts side 1,
         * Classification identifies a document type that has a back, and the
         * next fetch asks for side 2. Reusing the stage keeps the progress rail
         * honest — it is still the Document step, not a sixth one — while the
         * copy and captureType change so the customer is told to turn the
         * document over, and so the front end submits it as documentBack
         * (which maps to PrimaryDocument/side2Image) rather than overwriting
         * side 1.
         *
         * The title and body are fixed here rather than configurable: they
         * describe a platform behaviour ("now the other side"), not a market's
         * product copy, and every market's answer to it is the same sentence.
         */
        public Stage asSecondSide() {
            return new Stage(
                    name + "-side2",
                    kind,
                    prefix,
                    stage,
                    "Now the other side",
                    "Turn your document over and scan the back.",
                    cta == null ? "Scan the back" : cta,
                    "document-back",
                    accepted,
                    modules,
                    Boolean.TRUE,
                    alsoPrefixes);
        }

        /** True when this stage still has something to collect. */
        public boolean claims(List<String> outstanding) {
            return outstanding.stream().anyMatch(this::claimsRef);
        }

        /** True when {@code ref} is one this stage collects. */
        public boolean claimsRef(String ref) {
            return ref != null && prefixes().stream().anyMatch(ref::startsWith);
        }

        /**
         * Whether this stage runs even when Go never lists its elements as
         * outstanding.
         *
         * A journey can accept an element it does not advertise. Northbank's
         * journey on {@code gbggo4-demo} collects its document lazily — the
         * fetch carries instruction {@code LazySide2CollectionRequired}, and
         * {@code outstanding} never names {@code PrimaryDocument/} — yet
         * submitting a document to it returns {@code {"status":"success"}}
         * (verified against the live tenant, 2026-09-10). Selecting stages on
         * {@code outstanding} alone therefore skips the document screen and
         * sends the customer from address straight to selfie.
         *
         * Off by default, so a market whose journey does advertise its
         * elements is unaffected: a stage nothing claims stays skipped, which
         * is what stops a retired or unconfigured module rendering a screen
         * whose submit goes nowhere.
         */
        public boolean alwaysCollects() {
            return Boolean.TRUE.equals(alwaysCollect);
        }
    }
}
