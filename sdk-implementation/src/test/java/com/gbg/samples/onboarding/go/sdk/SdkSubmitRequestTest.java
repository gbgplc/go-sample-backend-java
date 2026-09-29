package com.gbg.samples.onboarding.go.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.gbg.gocore.utils.JSON;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The submit request's wire shape, as JSON — review findings 12, 15, 16, and
 * the SDK side of 10.
 */
class SdkSubmitRequestTest {

    private final SdkInteractionMapper mapper = new SdkInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

    private JsonNode wire(Map<String, Object> data) throws Exception {
        return JSON.getMapper().readTree(JSON.getMapper().writeValueAsString(
                mapper.toSubmitRequest("i-1", "int-1", data, "https://consent")));
    }

    private static List<String> participants(JsonNode wire) {
        List<String> ids = new ArrayList<>();
        wire.path("participants").forEach(p -> ids.add(p.path("domainElementId").asText()));
        return ids;
    }

    /** Findings 12 and 16: a null was sent as "null", and an unmapped key was declared but never sent. */
    @Test
    void nullValuesAndUnmappedKeysAreNeitherSentNorDeclared() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Gender", null);
        data.put("SomeFieldNoMappingKnows", "x");
        data.put("PersonalEmail/email", "a@b.com");

        JsonNode wire = wire(data);

        assertThat(participants(wire)).containsExactly("PersonalEmail");
        assertThat(wire.at("/context/subject/identity/gender").isMissingNode()).isTrue();
        assertThat(wire.toString()).doesNotContain("\"null\"");
    }

    /** Finding 15: the shapes the raw-HTTP client sends, which were verified live. */
    @Test
    void consentAndSelfieCarryTheirType() throws Exception {
        assertThat(wire(Map.of("shareWithClinicians", true)).at("/context/subject/consent/0/type").asText())
                .isEqualTo("explicit");
        assertThat(wire(Map.of("selfieImage", "img")).at("/context/subject/biometrics/0/type").asText())
                .isEqualTo("Selfie");
    }

    /** Finding 10, SDK side: the paths recorded are the ones the request actually uses. */
    @Test
    void goPathsPointAtWhereEachFieldLandsInTheRequest() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PersonalEmail/email", "a@b.com");
        data.put("WorkEmail/email", "c@d.com");
        data.put("CurrentAddress/country", "GBR");

        Map<String, String> paths = mapper.fieldsByGoPath(data);
        JsonNode wire = wire(data);

        assertThat(paths).containsExactly(
                Map.entry("context.subject.identity.emails.0.email", "PersonalEmail/email"),
                Map.entry("context.subject.identity.emails.1.email", "WorkEmail/email"),
                Map.entry("context.subject.identity.currentAddress.country", "CurrentAddress/country"));
        paths.keySet().forEach(path ->
                assertThat(wire.at("/" + path.replace('.', '/')).isMissingNode()).as(path).isFalse());
    }

    /** The error body is the one Go actually returned for two bad emails (live tenant, 2026-09-29). */
    @Test
    void goValidationProblemsMapBackToTheSubmittedFields() {
        String body = "{\"errors\":[{\"code\":\"4002\",\"name\":\"MISSING_FIELD\",\"problem\":"
                + "\"context.subject.identity.emails.0.email: Invalid email address; "
                + "context.subject.identity.emails.1.email: Invalid email address\","
                + "\"action\":\"Please check the request body\",\"location\":\"Body\"}]}";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PersonalEmail/email", "bad");
        data.put("WorkEmail/email", "also bad");

        Map<String, String> fields = GoSdkClient.rejectedFields(GoSdkClient.goProblems(body), mapper.fieldsByGoPath(data));

        assertThat(fields).containsExactly(
                Map.entry("PersonalEmail/email", "Invalid email address"),
                Map.entry("WorkEmail/email", "Invalid email address"));
    }
}
