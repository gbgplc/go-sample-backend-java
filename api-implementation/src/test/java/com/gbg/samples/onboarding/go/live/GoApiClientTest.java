package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.GoProperties;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GoApiClient's error handling against a stubbed Go: the 401/403 token retry
 * (review finding 6) and naming the fields Go rejected on a 400 (finding 10).
 */
class GoApiClientTest {

    private static final String BASE = "https://go.test/v2/captain/";
    private static final String PROCESSING_FETCH =
            "{\"instanceId\":\"i-1\",\"journey\":{\"status\":\"InProgress\"},\"processing\":true,\"outstanding\":[]}";

    private final GoTokenService tokens = mock(GoTokenService.class);
    private MockRestServiceServer go;
    private GoApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        go = MockRestServiceServer.bindTo(builder).build();
        GoProperties properties = new GoProperties("live", "eu", null, null, null, "id", "secret", null, null, BASE);
        client = new GoApiClient(properties, tokens,
                new DefaultInteractionMapper(new ScreenPlanProperties(List.of(), List.of())),
                mock(AppConfigProperties.class), builder);
    }

    @Test
    void aRejectedTokenIsInvalidatedAndTheCallRetriedOnceWithAFreshOne() {
        when(tokens.accessToken()).thenReturn("stale", "fresh");
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andExpect(header("Authorization", "Bearer stale"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));

        assertThat(client.fetchInteraction("i-1").interaction().kind()).isEqualTo(ScreenKind.PROCESSING);
        verify(tokens).invalidate("stale");
        go.verify();
    }

    @Test
    void aSecondRejectionIsNotRetriedAgain() {
        when(tokens.accessToken()).thenReturn("stale", "also-rejected");
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        OnboardingException ex = catchThrowableOfType(() -> client.fetchInteraction("i-1"), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        go.verify();
    }

    @Test
    void otherErrorsLeaveTheTokenAlone() {
        when(tokens.accessToken()).thenReturn("good");
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        OnboardingException ex = catchThrowableOfType(() -> client.fetchInteraction("i-1"), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
        verify(tokens, never()).invalidate("good");
    }

    /** The error body is the one Go actually returned for two bad emails (live tenant, 2026-09-29). */
    @Test
    void aGoValidationErrorNamesTheFieldsItRejected() {
        when(tokens.accessToken()).thenReturn("good");
        go.expect(method(HttpMethod.POST)).andExpect(requestTo(BASE + "journey/interaction/submit"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errors":[{"code":"4002","name":"MISSING_FIELD",
                        "problem":"context.subject.identity.emails.0.email: Invalid email address; context.subject.identity.emails.1.email: Invalid email address",
                        "action":"Please check the request body and correct the validation errors","location":"Body"}]}
                        """));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PersonalEmail/email", "not-an-email");
        data.put("WorkEmail/email", "also bad");
        data.put("MobilePhone/number", "+447700900000");

        OnboardingException ex = catchThrowableOfType(
                () -> client.submitInteraction("i-1", "int-1", data), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ex.fields()).containsExactly(
                Map.entry("PersonalEmail/email", "Invalid email address"),
                Map.entry("WorkEmail/email", "Invalid email address"));
    }

    /**
     * Review finding 14: Go dropping {@code instructions} used to leave the
     * last Side2Required cached, so the next capture — the selfie — was routed
     * into the back of the document.
     */
    @Test
    void aSide2RequiredGoNoLongerSendsIsNotLeftCached() {
        when(tokens.accessToken()).thenReturn("good");
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andRespond(withSuccess(
                "{\"instanceId\":\"i-1\",\"journey\":{\"status\":\"InProgress\"},\"processing\":true,"
                        + "\"outstanding\":[],\"instructions\":[\"Side2Required\"]}", MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andRespond(withSuccess(
                "{\"instanceId\":\"i-1\",\"journey\":{\"status\":\"InProgress\"},\"outstanding\":[\"Selfie/selfieImage\"]}",
                MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/submit"))
                .andExpect(content().string(containsString("\"biometrics\"")))
                .andExpect(content().string(not(containsString("side2Image"))))
                .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));

        client.fetchInteraction("i-1");
        client.fetchInteraction("i-1");
        client.submitInteraction("i-1", "int-1", Map.of("attachmentRef", "selfie-bytes"));

        go.verify();
    }

    @Test
    void aValidationErrorNamingNoSubmittedFieldStillFailsWithoutFields() {
        Map<String, String> fields = GoApiClient.rejectedFields(
                "resourceId is required", Map.of("context.subject.identity.gender", "Gender"));

        assertThat(fields).isEmpty();
    }
}
