package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST https://api.auth.gbgplc.com/as/token.oauth2 response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoTokenResponse(String access_token, String token_type, long expires_in) {
}
