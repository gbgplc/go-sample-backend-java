package com.gbg.samples.onboarding.go.sdk;

import com.gbg.samples.onboarding.config.GoSdkProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review finding 11: live mode with missing credentials fails at startup, naming what's missing. */
class GoSdkAuthServiceTest {

    private static GoSdkProperties props(String grantType, String clientId, String secret, String username, String password) {
        return new GoSdkProperties("live", "eu", "https://auth.test", "gbg.token", grantType,
                clientId, secret, username, password, null);
    }

    @Test
    void clientCredentialsNeedsOnlyTheClientIdAndSecret() {
        assertThatCode(() -> GoSdkAuthService.requireCredentials(props("client_credentials", "id", "secret", null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingClientSecretFailsStartup() {
        assertThatThrownBy(() -> GoSdkAuthService.requireCredentials(props("client_credentials", "id", " ", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GBG_CLIENT_SECRET");
    }

    @Test
    void thePasswordGrantAlsoNeedsUsernameAndPassword() {
        assertThatThrownBy(() -> GoSdkAuthService.requireCredentials(props("password", "id", "secret", "", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GBG_USERNAME")
                .hasMessageContaining("GBG_PASSWORD");
    }
}
