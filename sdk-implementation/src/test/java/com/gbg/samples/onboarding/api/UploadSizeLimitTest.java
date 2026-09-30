package com.gbg.samples.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the multipart-size half of the code-review finding
 * that a real capture upload (a phone photo of a document or selfie, easily
 * 2-5MB) always exceeded Boot's default 1MB limit and came back
 * "500, retryable" instead of a sane, non-retryable rejection.
 *
 * <p>Needs a real embedded server ({@code webEnvironment = RANDOM_PORT}),
 * unlike every other test in this package: Boot's multipart size limit is
 * enforced by the servlet container's own {@code MultipartConfigElement}
 * before a request reaches Spring MVC at all, and {@code MockMvc}'s MOCK
 * web environment has no real container underneath to enforce it — a
 * {@code MockMvc}-based version of this test passes a request Boot would
 * actually reject.
 *
 * <p>Overrides the real {@code 10MB} limit down to a few bytes rather than
 * sending an actual oversized body: the same enforcement path fires either
 * way (real {@code MultipartConfigElement} + real
 * {@code MaxUploadSizeExceededException}), but a genuinely multi-megabyte
 * request over {@code TestRestTemplate}'s default client hits an unrelated
 * transport quirk first — Tomcat resets the connection as soon as it sees
 * the request exceeds the limit, which races the client still streaming the
 * body and surfaces as a low-level chunked-encoding I/O error instead of the
 * clean HTTP response this test wants to assert on. A small file over a
 * small limit completes in one packet, before that race is even possible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.servlet.multipart.max-file-size=8", "spring.servlet.multipart.max-request-size=8"})
@ActiveProfiles("northbank")
class UploadSizeLimitTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void uploadOverTheConfiguredLimitIsPayloadTooLargeNotRetryable500() throws Exception {
        ResponseEntity<String> start = restTemplate.postForEntity("/v1/sessions", new HttpEntity<>("{}", jsonHeaders()), String.class);
        JsonNode startBody = mapper.readTree(start.getBody());
        String sessionId = startBody.get("sessionId").asText();
        String sessionCookie = start.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(sessionCookie).isNotNull();

        byte[] oversized = "this is more than eight bytes".getBytes(); // over the 8-byte limit overridden above

        MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        multipartBody.add("file", new org.springframework.core.io.ByteArrayResource(oversized) {
            @Override
            public String getFilename() {
                return "big.jpg";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(multipartBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/sessions/{id}/attachments", request, String.class, sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.get("retryable").asBoolean()).isFalse();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
