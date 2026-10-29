package com.gbg.samples.onboarding.go.live.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    /** A ref only resolves by its leaf when the leaf's entry belongs to the same domain element. */
    @Test
    void aPreviousAddressComponentDoesNotOverwriteTheCurrentAddress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PreviousAddresses/postalCode", "OX1 1AA");
        data.put("CurrentAddress/postalCode", "SW1A 2AA");

        GoInteractionSubmitRequest request = GoInteractionSubmitRequest.of("i-1", "int-1", data, "https://consent");

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) request.context().subject().get("identity");
        assertThat(identity.get("currentAddress")).isEqualTo(Map.of("postalCode", "SW1A 2AA"));
        assertThat(identity.get("previousAddresses")).isEqualTo(List.of(Map.of("postalCode", "OX1 1AA")));
        assertThat(request.participants()).containsExactly(
                new GoInteractionSubmitRequest.Participant("PreviousAddresses"),
                new GoInteractionSubmitRequest.Participant("CurrentAddress"));
    }

    @Test
    void aPreviousAddressesComponentsLandTogetherInItsOwnEntry() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PreviousAddresses/building", "1");
        data.put("PreviousAddresses/postalCode", "OX1 1AA");
        data.put("PreviousAddresses/country", "United Kingdom");

        GoInteractionSubmitRequest request = GoInteractionSubmitRequest.of("i-1", "int-1", data, "https://consent");

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) request.context().subject().get("identity");
        assertThat(identity.get("previousAddresses"))
                .isEqualTo(List.of(Map.of("building", "1", "postalCode", "OX1 1AA", "country", "GBR")));
        assertThat(request.participants()).containsOnly(new GoInteractionSubmitRequest.Participant("PreviousAddresses"));
        assertThat(GoInteractionSubmitRequest.fieldsByGoPath(data))
                .containsEntry("context.subject.identity.previousAddresses.0.postalCode", "PreviousAddresses/postalCode");
    }

    /** mobileNumber used to replace phones[] with an immutable list, so a landline before it was lost. */
    @Test
    void aLandlineIsKeptWhenAMobileNumberFollowsIt() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("LandlinePhone/number", "02079460000");
        data.put("mobileNumber", "07700900000");

        GoInteractionSubmitRequest request = GoInteractionSubmitRequest.of("i-1", "int-1", data, "https://consent");

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) request.context().subject().get("identity");
        assertThat(identity.get("phones")).isEqualTo(List.of(
                Map.of("type", "landline", "number", "02079460000"),
                Map.of("type", "mobile", "number", "07700900000")));
    }

    /** The reverse order used to throw: the landline was appended to mobileNumber's immutable list. */
    @Test
    void aLandlineAfterAMobileNumberIsAppended() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mobileNumber", "07700900000");
        data.put("LandlinePhone/number", "02079460000");

        GoInteractionSubmitRequest request = GoInteractionSubmitRequest.of("i-1", "int-1", data, "https://consent");

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) request.context().subject().get("identity");
        assertThat(identity.get("phones")).isEqualTo(List.of(
                Map.of("type", "mobile", "number", "07700900000"),
                Map.of("type", "landline", "number", "02079460000")));
    }
}
