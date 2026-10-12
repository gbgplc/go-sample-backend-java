package com.gbg.samples.onboarding.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** go-core-sdk 0.2.0 puts {@code /v2/captain} in each operation path, so its server URL must be the bare host. */
class GoSdkPropertiesTest {

    private static GoSdkProperties withBaseUrl(String baseUrl) {
        return new GoSdkProperties("live", "eu", "https://auth.test", "gbg.token", "client_credentials",
                "id", "secret", null, null, baseUrl);
    }

    @Test
    void theApiPathIsStrippedFromAConfiguredBaseUrl() {
        assertThat(withBaseUrl("https://eu.platform.go.gbgplc.com/v2/captain/").sdkServerUrl())
                .isEqualTo("https://eu.platform.go.gbgplc.com");
        assertThat(withBaseUrl("https://gbggo4-demo-eu.example.com/v2/captain").sdkServerUrl())
                .isEqualTo("https://gbggo4-demo-eu.example.com");
    }

    @Test
    void theApiPathIsStrippedWhateverItsCase() {
        assertThat(withBaseUrl("https://eu.platform.go.gbgplc.com/V2/Captain/").sdkServerUrl())
                .isEqualTo("https://eu.platform.go.gbgplc.com");
    }

    @Test
    void anyOtherPathPrefixIsKeptForTheSdkToAppendTo() {
        assertThat(withBaseUrl("https://gw.example.com/go/v2/captain/").sdkServerUrl())
                .isEqualTo("https://gw.example.com/go");
    }

    @Test
    void aBaseUrlWithoutTheApiPathIsLeftAsTheHost() {
        assertThat(withBaseUrl("https://eu.platform.go.gbgplc.com/").sdkServerUrl())
                .isEqualTo("https://eu.platform.go.gbgplc.com");
    }

    @Test
    void theRegionalDefaultIsTheBareHostToo() {
        assertThat(withBaseUrl(null).sdkServerUrl()).isEqualTo("https://eu.platform.go.gbgplc.com");
    }
}
