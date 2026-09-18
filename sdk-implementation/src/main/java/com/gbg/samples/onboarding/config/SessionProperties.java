package com.gbg.samples.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "session")
public record SessionProperties(
        @DefaultValue("30") long ttlMinutes,
        @DefaultValue("onboarding_session") String cookieName
) {
}
