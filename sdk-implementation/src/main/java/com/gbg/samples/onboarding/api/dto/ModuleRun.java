package com.gbg.samples.onboarding.api.dto;

/**
 * {@code outcome} is Go's own descriptive result for this module — e.g.
 * "Document Classified", "Extraction Successful", "No Match" — the same text
 * shown in the Go platform's own investigation UI. Worth showing because
 * {@code state} alone often cannot: a module with no positive/negative
 * verdict of its own (Document Classification, Extraction) always maps to
 * {@code Review} regardless of how it actually went, so the coloured badge
 * carries the state and this carries the detail.
 */
public record ModuleRun(String label, ModuleState state, String ms, String outcome) {
    public ModuleRun(String label, ModuleState state) {
        this(label, state, null, null);
    }

    public ModuleRun(String label, ModuleState state, String ms) {
        this(label, state, ms, null);
    }
}
