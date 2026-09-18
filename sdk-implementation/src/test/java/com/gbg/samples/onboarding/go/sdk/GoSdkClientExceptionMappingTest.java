package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.errors.APIException;
import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

/**
 * No dedicated test exists for {@code GoApiClient.call()}'s exception
 * mapping today — this is new, added while porting {@code call()} onto the
 * SDK's real exception type. The spike confirmed every generated operation
 * throws {@link APIException} (extending the abstract {@code GoException})
 * on 4XX/5XX, settling what the docs left ambiguous between {@code GoException}
 * and {@code APIException} (see {@code spike/sdk-jar-inspection/FINDINGS.md},
 * Q1) — this test locks in {@link GoSdkClient#call} classifying that
 * exception's {@code code()} the same way {@code GoApiClient.call()}
 * classified an HTTP status code.
 *
 * {@link GoSdkClient#call} is package-private specifically so this test can
 * reach it directly without going through Spring or a live Go call — the
 * other three constructor dependencies are irrelevant to this one method and
 * left null.
 */
class GoSdkClientExceptionMappingTest {

    private final GoSdkClient client = new GoSdkClient(null, null, null, null);

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> fakeRawResponse() {
        return (HttpResponse<InputStream>) mock(HttpResponse.class);
    }

    private static APIException apiException(int code) {
        return new APIException("Go said no", code, null, fakeRawResponse(), null);
    }

    @Test
    void notFoundMapsToSessionExpired() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw apiException(404); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void goneMapsToSessionExpired() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw apiException(410); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void badRequestMapsToValidationFailed() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw apiException(400); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void unprocessableEntityMapsToValidationFailed() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw apiException(422); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void tooManyRequestsMapsToRateLimited() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw apiException(429); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void anyOtherStatusMapsToUpstreamUnavailable() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw apiException(500); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void anUnexpectedRuntimeExceptionAlsoMapsToUpstreamUnavailable() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw new IllegalStateException("boom"); }), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void anOnboardingExceptionThrownInsideIsRethrownUnchanged() {
        OnboardingException original = OnboardingException.rateLimited("already classified");

        OnboardingException ex = catchThrowableOfType(
                () -> client.call(() -> { throw original; }), OnboardingException.class);

        assertThat(ex).isSameAs(original);
    }
}
