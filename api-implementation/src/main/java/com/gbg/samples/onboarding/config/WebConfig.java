package com.gbg.samples.onboarding.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The front end calls with {@code credentials: 'include'} so the session
 * cookie rides on every request — that requires an explicit origin allow-list
 * ({@code allowCredentials(true)} is incompatible with a wildcard origin).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppConfigProperties appConfig;

    public WebConfig(AppConfigProperties appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = appConfig.corsAllowedOrigins() == null
                ? new String[0]
                : appConfig.corsAllowedOrigins().toArray(new String[0]);
        registry.addMapping("/v1/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
