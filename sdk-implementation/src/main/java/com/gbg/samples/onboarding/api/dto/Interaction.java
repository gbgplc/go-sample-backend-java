package com.gbg.samples.onboarding.api.dto;

import java.util.List;

/**
 * The current interaction — the front end's routing signal (front-end
 * handoff, section 2, GET /interaction). Field names and shape must match
 * the TypeScript {@code Interaction} type in {@code onboarding-core}
 * byte-for-byte; both service implementations serve one contract.
 */
public record Interaction(
        String interactionId,
        ScreenKind kind,
        String stage,
        String eyebrow,
        String title,
        String body,
        String note,
        String cta,
        String secondaryCta,
        String captureType,
        List<String> accepted,
        List<FieldSchema> collects,
        List<ChoiceOption> options,
        List<ConsentCheck> checks,
        List<String> modules,
        List<ModuleRun> moduleRuns,
        Decision decision,
        String timing,
        List<SummaryRow> summary,
        String recordNote,
        List<StagePlanEntry> stagePlan
) {
}
