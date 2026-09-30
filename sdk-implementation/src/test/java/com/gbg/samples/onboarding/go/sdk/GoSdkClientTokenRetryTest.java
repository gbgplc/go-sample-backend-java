package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.errors.APIException;
import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review finding 6: a token Go rejects is invalidated and the call retried once. */
class GoSdkClientTokenRetryTest {

    private final GoSdkAuthService auth = mock(GoSdkAuthService.class);
    private final GoSdkClient client = new GoSdkClient(null, auth, null, null);

    @SuppressWarnings("unchecked")
    private static APIException apiException(int code) {
        return new APIException("Go said no", code, null, (HttpResponse<InputStream>) mock(HttpResponse.class), null);
    }

    @Test
    void aRejectedTokenIsInvalidatedAndTheCallRetriedWithAFreshOne() {
        when(auth.accessToken()).thenReturn("stale", "fresh");
        List<String> seen = new ArrayList<>();

        String result = client.authorized(token -> {
            seen.add(token);
            if (token.equals("stale")) throw apiException(401);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(seen).containsExactly("stale", "fresh");
        verify(auth).invalidate("stale");
    }

    @Test
    void aSecondRejectionIsClassifiedRatherThanRetriedAgain() {
        when(auth.accessToken()).thenReturn("stale", "also-rejected");
        List<String> seen = new ArrayList<>();

        OnboardingException ex = catchThrowableOfType(() -> client.call(() -> client.authorized(token -> {
            seen.add(token);
            throw apiException(403);
        })), OnboardingException.class);

        assertThat(seen).hasSize(2);
        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void otherErrorsLeaveTheTokenAlone() {
        when(auth.accessToken()).thenReturn("good");

        OnboardingException ex = catchThrowableOfType(() -> client.call(() -> client.authorized(token -> {
            throw apiException(404);
        })), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
        verify(auth, never()).invalidate("good");
    }
}
