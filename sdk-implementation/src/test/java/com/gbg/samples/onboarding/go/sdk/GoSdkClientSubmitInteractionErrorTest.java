package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.InteractionSubmitResponse;
import com.gbg.gocore.models.InteractionSubmitResponseStatus;
import com.gbg.gocore.models.operations.SubmitInteractionResponse;
import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for the code-review finding that {@code submitInteraction}
 * discarded Go's submit response entirely and marked the stage completed
 * regardless — so a 200 submit whose body was not a success was silently
 * reported to the caller as one. {@link GoSdkClient#throwIfSubmitError} is
 * package-private so it can be exercised directly against a hand-built
 * {@link SubmitInteractionResponse} without a live Go call.
 */
class GoSdkClientSubmitInteractionErrorTest {

    private final GoSdkClient client = new GoSdkClient(null, null, null, null);

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> fakeRawResponse() {
        return (HttpResponse<InputStream>) mock(HttpResponse.class);
    }

    private static SubmitInteractionResponse responseWith(InteractionSubmitResponse body) {
        return new SubmitInteractionResponse("application/json", 200, fakeRawResponse(), body);
    }

    @Test
    void aNonSuccessStatusIsThrownNotSwallowed() {
        InteractionSubmitResponse error = new InteractionSubmitResponse(InteractionSubmitResponseStatus.of("error"));

        OnboardingException ex = catchThrowableOfType(
                () -> client.throwIfSubmitError(responseWith(error)), OnboardingException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void noBodyAtAllIsNotASuccess() {
        OnboardingException ex = catchThrowableOfType(
                () -> client.throwIfSubmitError(responseWith(null)), OnboardingException.class);

        assertThat(ex).isNotNull();
    }

    @Test
    void aGenuineSuccessDoesNotThrow() {
        InteractionSubmitResponse success = new InteractionSubmitResponse(InteractionSubmitResponseStatus.SUCCESS);

        assertThatCode(() -> client.throwIfSubmitError(responseWith(success))).doesNotThrowAnyException();
    }
}
