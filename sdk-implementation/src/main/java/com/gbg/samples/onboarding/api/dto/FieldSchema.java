package com.gbg.samples.onboarding.api.dto;

public record FieldSchema(
        String name,
        String label,
        String type,
        String placeholder,
        String helperText,
        Boolean required
) {
    public static FieldSchema of(String name, String label, String placeholder) {
        return new FieldSchema(name, label, null, placeholder, null, null);
    }

    public static FieldSchema of(String name, String label, String placeholder, String helperText) {
        return new FieldSchema(name, label, null, placeholder, helperText, null);
    }

    public FieldSchema withType(String type) {
        return new FieldSchema(name, label, type, placeholder, helperText, required);
    }
}
