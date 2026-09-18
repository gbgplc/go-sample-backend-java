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
        String recordNote,
        /**
         * The checks could not run, as opposed to running and declining.
         *
         * Carried explicitly because the two are indistinguishable from
         * {@code decision} alone — both arrive as {@code fail} — and they mean
         * opposite things to the customer: a decline is a verdict to appeal,
         * an error is a reason to try again. The service knows which it is
         * (a module reported an error and Go supplied an action for it), so it
         * says so rather than leaving each client to infer it from the shape
         * of the response and get it wrong.
         */
        boolean systemError
) {
}
