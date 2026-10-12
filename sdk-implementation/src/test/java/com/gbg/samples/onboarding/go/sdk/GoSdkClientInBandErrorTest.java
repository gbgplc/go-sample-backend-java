package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.utils.JSON;
import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.0 no longer models a 200 body carrying {@code {status: "error", code}}
 * on fetch or state; it arrives as the union's raw-JSON fallback, and
 * {@link GoSdkClient#inBandError} classifies it by code the way 0.1.0's
 * typed in-band error was.
 */
class GoSdkClientInBandErrorTest {

    private static OnboardingException classify(String json) throws Exception {
        return GoSdkClient.inBandError(JSON.getMapper().readTree(json));
    }

    @Test
    void aGoneInstanceIsASessionExpiry() throws Exception {
        assertThat(classify("{\"status\":\"error\",\"code\":410,\"message\":\"gone\"}").code())
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void aNumericStringCodeIsReadToo() throws Exception {
        assertThat(classify("{\"status\":\"error\",\"code\":\"429\"}").code()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void anErrorWithNoUsableCodeIsAnUpstreamFailure() throws Exception {
        assertThat(classify("{\"status\":\"error\"}").code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void anythingThatIsNotAnErrorIsLeftToTheCaller() throws Exception {
        assertThat(classify("{\"status\":\"something-new\"}")).isNull();
        assertThat(GoSdkClient.inBandError(null)).isNull();
    }
}
