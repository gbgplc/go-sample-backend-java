package com.gbg.samples.onboarding.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * go-core-sdk 0.2.0-alpha02's operation paths start at {@code /journey/...},
 * so its server URL must carry {@code /v2/captain} — the reverse of alpha01.
 */
class GoSdkPropertiesTest {

    private static GoSdkProperties withBaseUrl(String baseUrl) {
        return new GoSdkProperties("live", "eu", "https://auth.test", "gbg.token", "client_credentials",
                "id", "secret", null, null, baseUrl);
    }

    @Test
    void theApiPathIsKeptAndTheTrailingSlashDropped() {
        assertThat(withBaseUrl("https://eu.platform.go.gbgplc.com/v2/captain/").sdkServerUrl())
                .isEqualTo("https://eu.platform.go.gbgplc.com/v2/captain");
        assertThat(withBaseUrl("https://gbggo4-demo-eu.example.com/v2/captain").sdkServerUrl())
                .isEqualTo("https://gbggo4-demo-eu.example.com/v2/captain");
    }

    @Test
    void aGatewayPrefixIsKept() {
        assertThat(withBaseUrl("https://gw.example.com/go/v2/captain/").sdkServerUrl())
                .isEqualTo("https://gw.example.com/go/v2/captain");
    }

    /** Matches the SDK's own SERVERS entry for eu, which the regional default is used in place of. */
    @Test
    void theRegionalDefaultMatchesTheSdksServerList() {
        assertThat(withBaseUrl(null).sdkServerUrl()).isEqualTo(com.gbg.gocore.Go.SERVERS[0]);
    }
}
