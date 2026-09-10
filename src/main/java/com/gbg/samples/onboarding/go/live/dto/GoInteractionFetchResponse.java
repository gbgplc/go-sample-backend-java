package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** POST {baseUrl}journey/interaction/fetch response — see /docs/go-v2/api-reference/endpoint/fetch-interaction. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoInteractionFetchResponse(
        String instanceId,
        Journey journey,
        String interactionId,
        Map<String, Object> interaction,
        boolean processing,
        List<String> outstanding,
        List<String> instructions,
        GoResult result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journey(String status) {
    }

    /**
     * Every domain element ref this interaction collects, required or not.
     *
     * {@code outstanding} is the narrower field: only what Go is currently
     * blocking on. An element whose parent is optional never appears there —
     * the Northbank journey lists 37 refs under {@code collects} (all of
     * {@code PrimaryDocument/*}, both emails, both phones, MothersMaidenName,
     * Gender, NationalInsuranceNumber) while {@code outstanding} names six.
     * Building screens from {@code outstanding} alone drops most of the
     * journey's own pages, which is what left the document, personal-details
     * and contact-details screens unreachable.
     *
     * Empty when the interaction carries no collects — a mock, or a market
     * with no live journey — and callers fall back to {@code outstanding}.
     */
    public List<Collect> collects() {
        if (interaction == null) return List.of();
        if (!(interaction.get("collects") instanceof List<?> entries)) return List.of();
        List<Collect> parsed = new java.util.ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map && map.get("ref") != null) {
                parsed.add(new Collect(
                        String.valueOf(map.get("ref")),
                        map.get("spec") == null ? null : String.valueOf(map.get("spec")),
                        map.get("parentSpec") == null ? null : String.valueOf(map.get("parentSpec"))));
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * One collectable domain element ref.
     *
     * {@code spec} is the field's own requirement, {@code parentSpec} its
     * element group's. {@code PrimaryDocument/side1Image} on the Northbank
     * journey is {@code spec=required, parentSpec=optional} — required *if* a
     * document is supplied at all, which is why Go leaves it out of
     * {@code outstanding} yet still processes it when submitted.
     */
    public record Collect(String ref, String spec, String parentSpec) {
        public boolean required() {
            return "required".equalsIgnoreCase(spec);
        }
    }
}
