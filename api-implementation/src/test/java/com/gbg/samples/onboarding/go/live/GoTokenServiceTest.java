package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.config.GoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review finding 11: live mode with missing credentials fails at startup, naming what's missing. */
class GoTokenServiceTest {

    private static GoProperties props(String grantType, String clientId, String secret, String username, String password) {
        return new GoProperties("live", "eu", "https://auth.test", "gbg.token", grantType,
                clientId, secret, username, password, null);
    }

    @Test
    void clientCredentialsNeedsOnlyTheClientIdAndSecret() {
        assertThatCode(() -> GoTokenService.requireCredentials(props("client_credentials", "id", "secret", null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingClientSecretFailsStartup() {
        assertThatThrownBy(() -> GoTokenService.requireCredentials(props("client_credentials", "id", " ", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GBG_CLIENT_SECRET");
    }

    @Test
    void thePasswordGrantAlsoNeedsUsernameAndPassword() {
        assertThatThrownBy(() -> GoTokenService.requireCredentials(props("password", "id", "secret", "", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GBG_USERNAME")
                .hasMessageContaining("GBG_PASSWORD");
    }
}
