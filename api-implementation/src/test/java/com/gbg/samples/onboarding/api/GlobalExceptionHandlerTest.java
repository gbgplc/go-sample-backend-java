package com.gbg.samples.onboarding.api;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for the code-review finding that every one of these —
 * malformed JSON, a missing multipart part, an unsupported content-type, a
 * disallowed method, an unmatched path, and an oversized upload — used to
 * fall through {@code GlobalExceptionHandler}'s old catch-all and come back
 * "500, retryable", which is wrong on both counts: the status is wrong, and
 * retrying an identically-malformed request can never succeed. Each of these
 * asserts the real status and a non-retryable envelope instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("northbank")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private record StartedSession(String id, Cookie cookie) {
    }

    private StartedSession startSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        String id = mapper.readTree(result.getResponse().getContentAsString()).get("sessionId").asText();
        Cookie cookie = result.getResponse().getCookie("onboarding_session");
        return new StartedSession(id, cookie);
    }

    private String startSessionAndGetId() throws Exception {
        return startSession().id();
    }

    @Test
    void malformedJsonIsBadRequestNotRetryable500() throws Exception {
        String sessionId = startSessionAndGetId();

        mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void missingFilePartOnAttachmentsIsBadRequestNotRetryable500() throws Exception {
        String sessionId = startSessionAndGetId();

        // A multipart request with no "file" part at all — MissingServletRequestPartException.
        mockMvc.perform(multipart("/v1/sessions/{id}/attachments", sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void unsupportedMediaTypeIsRejectedAsSuch() throws Exception {
        String sessionId = startSessionAndGetId();

        mockMvc.perform(post("/v1/sessions/{id}/interaction", sessionId)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void wrongMethodIsRejectedAsMethodNotAllowed() throws Exception {
        // /v1/sessions is POST-only.
        mockMvc.perform(get("/v1/sessions"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mockMvc.perform(get("/v1/this-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // The over-the-limit-upload case is NOT here: MockMvc's MOCK web
    // environment dispatches straight into the DispatcherServlet with no
    // real servlet container underneath, so Boot's multipart size limit
    // (enforced by the container's own MultipartConfigElement before a
    // request even reaches Spring) never actually fires — the request comes
    // back 200. See UploadSizeLimitTest, which spins up a real embedded
    // server for exactly this reason.
}
