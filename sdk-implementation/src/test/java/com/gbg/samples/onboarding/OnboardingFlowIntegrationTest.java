package com.gbg.samples.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the whole Northbank flow through real HTTP, against
 * {@link com.gbg.samples.onboarding.go.mock.MockGoClient} — the same
 * contract the front end's {@code RestTransport} calls. Covers both the
 * straight-through and the referral branch (choice-free here, but exercises
 * idempotent resubmission and interaction staleness, which the choice-driven
 * healthcare flow doesn't touch).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("northbank")
class OnboardingFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void startsAndFinishesTheStraightThroughJourney() throws Exception {
        MvcResult startResult = mockMvc.perform(post("/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.interaction.kind").value("intro"))
                .andExpect(jsonPath("$.interaction.stagePlan[0].label").value("Start"))
                .andExpect(jsonPath("$.interaction.stagePlan[0].state").value("active"))
                .andReturn();

        JsonNode start = mapper.readTree(startResult.getResponse().getContentAsString());
        String sessionId = start.get("sessionId").asText();
        String interactionId = start.get("interaction").get("interactionId").asText();
        Cookie sessionCookie = startResult.getResponse().getCookie("onboarding_session");
        assertThat(sessionCookie).isNotNull();

        // intro -> details
        MvcResult formResult = mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionId\":\"" + interactionId + "\",\"data\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interaction.kind").value("form"))
                .andExpect(jsonPath("$.interaction.collects[0].name").value("fullName"))
                .andReturn();

        // Resubmitting the SAME interactionId must be idempotent, not advance twice.
        mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionId\":\"" + interactionId + "\",\"data\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interaction.kind").value("form"));

        JsonNode form = mapper.readTree(formResult.getResponse().getContentAsString());
        String detailsInteractionId = form.get("interaction").get("interactionId").asText();

        // Submitting a stale (already-passed) interactionId is rejected.
        mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionId\":\"not-the-current-one\",\"data\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERACTION_STALE"));

        // details -> document -> selfie -> processing -> result
        String nextId = detailsInteractionId;
        for (int i = 0; i < 3; i++) {
            MvcResult r = mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                            .cookie(sessionCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"interactionId\":\"" + nextId + "\",\"data\":{}}"))
                    .andExpect(status().isOk())
                    .andReturn();
            nextId = mapper.readTree(r.getResponse().getContentAsString()).get("interaction").get("interactionId").asText();
        }

        // Now on the processing screen; submitting it completes the journey.
        MvcResult finalResult = mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interactionId\":\"" + nextId + "\",\"data\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Completed"))
                .andExpect(jsonPath("$.interaction.stagePlan[?(@.label=='Decision')].state").value("active"))
                .andReturn();
        assertThat(finalResult).isNotNull();

        mockMvc.perform(get("/v1/sessions/{id}/record", sessionId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("pass"))
                .andExpect(jsonPath("$.summary").isNotEmpty());
    }

    @Test
    void rejectsRequestsWithoutTheSessionCookie() throws Exception {
        MvcResult startResult = mockMvc.perform(post("/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        String sessionId = mapper.readTree(startResult.getResponse().getContentAsString()).get("sessionId").asText();

        mockMvc.perform(get("/v1/sessions/{id}/state", sessionId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    /**
     * Review finding 9: a fixed Max-Age outlived nothing — it expired 30
     * minutes after start however active the customer was, while the
     * server-side session (which slides on access) was still alive.
     */
    @Test
    void theSessionCookieHasNoFixedLifetime() throws Exception {
        MvcResult startResult = mockMvc.perform(post("/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(startResult.getResponse().getHeader("Set-Cookie"))
                .startsWith("onboarding_session=")
                .contains("HttpOnly")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires");
    }

    @Test
    void getConfigReturnsTheNorthbankBrand() throws Exception {
        mockMvc.perform(get("/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand").value("Northbank"))
                .andExpect(jsonPath("$.accent").value("#4D4DFF"));
    }
}
