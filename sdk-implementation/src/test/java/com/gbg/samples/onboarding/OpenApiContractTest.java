package com.gbg.samples.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the generated OpenAPI spec — {@code /v3/api-docs}, springdoc's own
 * reflection of {@link com.gbg.samples.onboarding.api.SessionController} —
 * against the endpoints and field names a front end (this repo's own, or any
 * other implementation of the same contract) is built against.
 *
 * {@code SessionController}'s Javadoc says these DTOs "must match
 * {@code RestTransport} in the front end's {@code onboarding-core} package
 * to the field name" — previously a promise only checked by a human reading
 * both codebases. This test makes the spec the enforced contract instead:
 * renaming or dropping a field a front end reads by name now fails a test
 * here, in the same commit, rather than surfacing later as a silent runtime
 * mismatch nobody connected back to this change.
 *
 * Deliberately asserts on property presence, not full-document equality — a
 * springdoc/library version bump can reformat parts of the spec unrelated to
 * the actual contract, and a byte-exact snapshot would fail on that noise as
 * loudly as on a real breaking change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("northbank")
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyEndpointInTheFrontEndHandoffIsStillDocumented() throws Exception {
        JsonNode paths = fetchSpec().get("paths");

        assertThat(fieldNames(paths)).containsExactlyInAnyOrder(
                "/v1/sessions",
                "/v1/sessions/{id}/interaction",
                "/v1/sessions/{id}/state",
                "/v1/sessions/{id}/record",
                "/v1/sessions/{id}/attachments",
                "/v1/config"
        );
    }

    @Test
    void interactionSchemaKeepsEveryFieldTheFrontEndReadsByName() throws Exception {
        assertThat(fieldNames(schemaNamed("Interaction").get("properties"))).containsExactlyInAnyOrder(
                "interactionId", "kind", "stage", "eyebrow", "title", "body", "note", "cta",
                "secondaryCta", "captureType", "accepted", "collects", "options", "checks",
                "modules", "moduleRuns", "decision", "timing", "summary", "recordNote", "stagePlan"
        );
    }

    @Test
    void errorEnvelopeSchemaKeepsEveryFieldTheFrontEndReadsByName() throws Exception {
        assertThat(fieldNames(schemaNamed("ErrorEnvelope").get("properties"))).containsExactlyInAnyOrder(
                "code", "http", "message", "fields", "retryable"
        );
    }

    @Test
    void startSessionResponseSchemaKeepsEveryFieldTheFrontEndReadsByName() throws Exception {
        assertThat(fieldNames(schemaNamed("StartSessionResponse").get("properties"))).containsExactlyInAnyOrder(
                "sessionId", "status", "interaction"
        );
    }

    @Test
    void appConfigResponseSchemaKeepsEveryFieldTheFrontEndReadsByName() throws Exception {
        assertThat(fieldNames(schemaNamed("AppConfigResponse").get("properties"))).containsExactlyInAnyOrder(
                "brand", "mark", "tagline", "accent", "accentSoft", "helpLine", "journeyName", "resourceId"
        );
    }

    private JsonNode schemaNamed(String name) throws Exception {
        JsonNode schema = fetchSpec().at("/components/schemas/" + name);
        assertThat(schema.isMissingNode())
                .as("schema '%s' should be present in the generated OpenAPI spec", name)
                .isFalse();
        return schema;
    }

    private JsonNode fetchSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private static List<String> fieldNames(JsonNode objectNode) {
        Iterator<String> names = objectNode.fieldNames();
        Spliterator<String> spliterator = Spliterators.spliteratorUnknownSize(names, Spliterator.ORDERED);
        return StreamSupport.stream(spliterator, false).toList();
    }
}
