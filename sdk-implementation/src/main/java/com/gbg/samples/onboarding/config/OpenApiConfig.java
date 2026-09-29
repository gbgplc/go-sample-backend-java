package com.gbg.samples.onboarding.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the REST contract from "Market onboarding applications —
 * front-end handoff", section 2 as a live OpenAPI 3 document, served at
 * {@code /v3/api-docs} with a browsable UI at {@code /swagger-ui/index.html}.
 * Every deployment of this service serves the same API shape — only the
 * title reflects which market this instance fronts, pulled from the same
 * {@link AppConfigProperties} that drives {@code GET /v1/config}.
 */
@Configuration
public class OpenApiConfig {

    /**
     * The key the session-cookie scheme is registered under, and that every
     * {@code @SecurityRequirement} names. Fixed, unlike the cookie name
     * itself ({@code session.cookie-name}): when the key tracked the cookie
     * name, renaming the cookie left the requirements pointing at a scheme
     * that no longer existed and Swagger UI stopped sending it.
     */
    public static final String SESSION_SCHEME = "sessionCookie";

    @Bean
    public OpenAPI onboardingOpenApi(AppConfigProperties appConfig, SessionProperties sessionProperties) {
        String cookieName = sessionProperties.cookieName();
        return new OpenAPI()
                .info(new Info()
                        .title(appConfig.brand() + " — Onboarding Service API")
                        .description("Thin-proxy REST contract between the " + appConfig.brand()
                                + " front end and GBG Go. The front end never talks to Go directly; "
                                + "this service holds the credentials, mints tokens, and forwards the "
                                + "interaction loop. Session identity rides on the '" + cookieName
                                + "' HTTP-only cookie set by POST /v1/sessions — every other endpoint "
                                + "requires it.")
                        .version("v1")
                        .contact(new Contact().name("GBG Go samples")))
                .addServersItem(new Server().url("/").description("This deployment"))
                .components(new Components()
                        .addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(cookieName)
                                .description("Set by POST /v1/sessions. Required on every other /v1/sessions/{id}/** call.")));
    }
}
