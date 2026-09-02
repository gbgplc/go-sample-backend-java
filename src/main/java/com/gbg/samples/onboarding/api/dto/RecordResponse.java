package com.gbg.samples.onboarding.api.dto;

import java.util.List;

/** GET /record — the verification record shown on the final screen. */
public record RecordResponse(
        Decision decision,
        String title,
        String timing,
        String body,
        String cta,
        List<ModuleRun> moduleRuns,
        List<SummaryRow> summary,
        String recordNote
) {
}
