package com.gbg.samples.onboarding.go.live.dto;

import java.util.List;
import java.util.Map;

/** POST {baseUrl}journey/interaction/submit request — see /docs/go-v2/api-reference/endpoint/submit-interaction. */
public record GoInteractionSubmitRequest(String instanceId, String interactionId, List<Participant> participants) {

    /**
     * Passes the front end's field-name-keyed submission through as one
     * participant record per field, using the field name as the domain
     * element id. A journey configured in the Go builder may use different
     * domain element ids than the sample's invented field names (fullName vs
     * FullName, etc.) — reconciling that mapping against a real journey's
     * schema is the next step once one is published; see fetch-tasks-with-schema.
     */
    public record Participant(String domainElementId, Object value) {
    }

    public static GoInteractionSubmitRequest of(String instanceId, String interactionId, Map<String, Object> data) {
        List<Participant> participants = data == null ? List.of() : data.entrySet().stream()
                .map(e -> new Participant(e.getKey(), e.getValue()))
                .toList();
        return new GoInteractionSubmitRequest(instanceId, interactionId, participants);
    }
}
