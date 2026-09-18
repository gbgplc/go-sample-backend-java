package com.gbg.samples.onboarding.go.mock;

import com.gbg.samples.onboarding.api.dto.ConsentCheck;
import com.gbg.samples.onboarding.api.dto.Decision;
import com.gbg.samples.onboarding.api.dto.FieldSchema;
import com.gbg.samples.onboarding.api.dto.ModuleRun;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * One scripted step in a mock scenario — the Java-side twin of
 * {@code MockStepDef} in the TypeScript {@code onboarding-core} package.
 * Almost every field maps straight onto {@code Interaction}; a {@code result}
 * step only becomes the scenario's terminal step (and flips the reported
 * status to Completed) once it carries a non-empty {@code summary}.
 */
@Value
@Builder
public class ScenarioStep {
    ScreenKind kind;
    String stage;
    String eyebrow;
    String title;
    String body;
    String note;
    String cta;
    String secondaryCta;
    String captureType;
    @Singular("accepted") List<String> accepted;
    @Singular List<FieldSchema> fields;
    @Singular List<ScenarioOption> options;
    @Singular List<ConsentCheck> checks;
    @Singular List<String> modules;
    @Singular List<ModuleRun> moduleRuns;
    Decision decision;
    String timing;
    @Singular("summaryRow") List<SummaryRow> summary;
    String recordNote;
}
