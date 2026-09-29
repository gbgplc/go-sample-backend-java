package com.gbg.samples.onboarding.go.live.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoInteractionSubmitRequestTest {

    /** Review finding 12: a null used to be sent as the string "null" (e.g. email:"null" → Go 400). */
    @Test
    void aNullValueIsLeftOutRatherThanSentAsTheStringNull() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Gender", null);
        data.put("PersonalEmail/email", "a@b.com");

        GoInteractionSubmitRequest request = GoInteractionSubmitRequest.of("i-1", "int-1", data, "https://consent");

        assertThat(request.participants()).containsExactly(new GoInteractionSubmitRequest.Participant("PersonalEmail"));
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) request.context().subject().get("identity");
        assertThat(identity).doesNotContainKey("gender").containsKey("emails");
    }

    @Test
    void fieldsByGoPathNamesWhereEachFieldLands() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PersonalEmail/email", "a@b.com");
        data.put("WorkEmail/email", "c@d.com");
        data.put("CurrentAddress/postalCode", "AB1 2CD");
        data.put("Gender", null);

        assertThat(GoInteractionSubmitRequest.fieldsByGoPath(data)).containsExactly(
                Map.entry("context.subject.identity.emails.0.email", "PersonalEmail/email"),
                Map.entry("context.subject.identity.emails.1.email", "WorkEmail/email"),
                Map.entry("context.subject.identity.currentAddress.postalCode", "CurrentAddress/postalCode"));
    }
}
