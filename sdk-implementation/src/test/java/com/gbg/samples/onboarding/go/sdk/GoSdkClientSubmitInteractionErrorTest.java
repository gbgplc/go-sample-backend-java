package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.operations.StatusSuccess;
import com.gbg.gocore.models.operations.Success;
import com.gbg.gocore.models.operations.SubmitInteractionError;
import com.gbg.gocore.models.operations.SubmitInteractionResponse;
import com.gbg.gocore.models.operations.SubmitInteractionResponseBody;
import com.gbg.gocore.models.operations.SubmitInteractionStatusError;
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
 * regardless — so a submission Go rejected in-band (a {@code 200} response
 * whose body is {@code SubmitInteractionError}, not a transport-level
 * {@code APIException}) was silently reported to the caller as a success.
 * The fetch path already handled this shape via {@code classifyInBandError};
 * submit now goes through the same check ({@link GoSdkClient#throwIfSubmitError}),
 * package-private for the same reason {@link GoSdkClient#call} is: it can be
 * exercised directly against a hand-built {@link SubmitInteractionResponse}
 * without a live Go call.
 */
class GoSdkClientSubmitInteractionErrorTest {

    private final GoSdkClient client = new GoSdkClient(null, null, null, null);

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> fakeRawResponse() {
        return (HttpResponse<InputStream>) mock(HttpResponse.class);
    }

    private static SubmitInteractionResponse responseWith(SubmitInteractionResponseBody body) {
        return new SubmitInteractionResponse("application/json", 200, fakeRawResponse(), body);
    }

    @Test
    void inBandErrorIsThrownNotSwallowed() {
        SubmitInteractionError error = new SubmitInteractionError(SubmitInteractionStatusError.ERROR, 410,
                "The interaction has moved on.");

        OnboardingException ex = catchThrowableOfType(
                () -> client.throwIfSubmitError(responseWith(error)), OnboardingException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void aGenuineSuccessDoesNotThrow() {
        Success success = new Success(StatusSuccess.SUCCESS);

        assertThatCode(() -> client.throwIfSubmitError(responseWith(success))).doesNotThrowAnyException();
    }

    @Test
    void noBodyAtAllDoesNotThrow() {
        assertThatCode(() -> client.throwIfSubmitError(responseWith(null))).doesNotThrowAnyException();
    }
}
