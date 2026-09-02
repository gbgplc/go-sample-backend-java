package com.gbg.samples.onboarding.api.dto;

public record ModuleRun(String label, ModuleState state, String ms) {
    public ModuleRun(String label, ModuleState state) {
        this(label, state, null);
    }
}
