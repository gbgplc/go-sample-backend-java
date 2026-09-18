package com.gbg.samples.onboarding.api.dto;

public record ConsentCheck(String name, String label, String detail, Boolean defaultChecked) {
    public static ConsentCheck of(String name, String label, String detail) {
        return new ConsentCheck(name, label, detail, null);
    }
}
