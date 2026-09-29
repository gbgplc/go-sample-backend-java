package com.gbg.samples.onboarding.api;

import com.gbg.samples.onboarding.api.dto.AppConfigResponse;
import com.gbg.samples.onboarding.api.dto.AttachmentResponse;
import com.gbg.samples.onboarding.api.dto.ErrorEnvelope;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StartSessionRequest;
import com.gbg.samples.onboarding.api.dto.StartSessionResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionRequest;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.config.OpenApiConfig;
import com.gbg.samples.onboarding.config.SessionProperties;
import com.gbg.samples.onboarding.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


/**
 * The REST contract from "Market onboarding applications — front-end
 * handoff", section 2 — one v1 for whichever market this deployment fronts.
 * Endpoint shapes here must match {@code RestTransport} in the front end's
 * {@code onboarding-core} package to the field name.
 *
 * Session identity rides on an HTTP-only cookie set here on
 * {@code POST /v1/sessions} (section 6, open decision: front-end
 * authentication) — every other call must present it, matched against the
 * token recorded for that session id.
 *
 * Annotated for springdoc-openapi — see {@code /v3/api-docs} and
 * {@code /swagger-ui/index.html} on a running instance.
 */
@RestController
@Tag(name = "Onboarding session", description = "The thin-proxy REST contract a front end drives to run one onboarding journey.")
public class SessionController {

    private final SessionService sessionService;
    private final SessionProperties sessionProperties;

    public SessionController(SessionService sessionService, SessionProperties sessionProperties) {
        this.sessionService = sessionService;
        this.sessionProperties = sessionProperties;
    }

    @Operation(
            summary = "Start a journey",
            description = "Starts a journey and returns the first interaction inline to save a round trip. "
                    + "Sets the session cookie on the response — capture it (browsers do this automatically "
                    + "with credentials: 'include'; other clients need a cookie jar)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session started"),
            @ApiResponse(responseCode = "503", description = "Could not reach Go",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @PostMapping("/v1/sessions")
    public StartSessionResponse startSession(
            @RequestBody(required = false) StartSessionRequest request,
            @Parameter(description = "Mock-mode only — jumps straight to a named scenario (e.g. 'refer', 'denied'). Ignored by a live Go client.")
            @RequestParam(name = "mock_scenario", required = false) String scenarioHint,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        var prefill = request == null ? null : request.prefill();
        SessionService.Started started = sessionService.startSession(prefill, scenarioHint);
        setSessionCookie(response, started.cookieToken(), httpRequest.isSecure());
        return started.body();
    }

    @Operation(summary = "Fetch the current interaction",
            description = "Re-fetches whichever interaction the session is currently on — the front end's routing signal for which screen to render.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current interaction"),
            @ApiResponse(responseCode = "410", description = "Session missing, expired, or cookie mismatch",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @SecurityRequirement(name = OpenApiConfig.SESSION_SCHEME)
    @GetMapping("/v1/sessions/{id}/interaction")
    public Interaction getInteraction(
            @PathVariable("id") String sessionId,
            @CookieValue(name = "${session.cookie-name}", required = false) String sessionCookie
    ) {
        return sessionService.getInteraction(sessionId, sessionCookie);
    }

    @Operation(summary = "Submit the current interaction",
            description = "Idempotent on interactionId — resubmitting the same one returns the cached result rather than advancing twice. "
                    + "Returns the next interaction, or a terminal status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Advanced to the next interaction, or reached a terminal status"),
            @ApiResponse(responseCode = "409", description = "interactionId doesn't match the session's current one — the journey moved on; re-fetch and re-render",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
            @ApiResponse(responseCode = "410", description = "Session missing, expired, or cookie mismatch",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
            @ApiResponse(responseCode = "422", description = "Field-level validation failure",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @SecurityRequirement(name = OpenApiConfig.SESSION_SCHEME)
    @PostMapping("/v1/sessions/{id}/interaction")
    public SubmitInteractionResponse submitInteraction(
            @PathVariable("id") String sessionId,
            @Valid @RequestBody SubmitInteractionRequest request,
            @CookieValue(name = "${session.cookie-name}", required = false) String sessionCookie
    ) {
        return sessionService.submitInteraction(sessionId, sessionCookie, request.interactionId(), request.data());
    }

    @Operation(summary = "Journey status and, once reached, the decision",
            description = "Polled while a processing screen is showing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current status"),
            @ApiResponse(responseCode = "410", description = "Session missing, expired, or cookie mismatch",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @SecurityRequirement(name = OpenApiConfig.SESSION_SCHEME)
    @GetMapping("/v1/sessions/{id}/state")
    public StateResponse getState(
            @PathVariable("id") String sessionId,
            @CookieValue(name = "${session.cookie-name}", required = false) String sessionCookie
    ) {
        return sessionService.getState(sessionId, sessionCookie);
    }

    @Operation(summary = "The verification record for the final screen",
            description = "Journey name and version, reference, timestamps, elapsed time, per-module outcome, and the deciding module where the outcome wasn't a straight pass.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verification record"),
            @ApiResponse(responseCode = "410", description = "Session missing, expired, or cookie mismatch",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @SecurityRequirement(name = OpenApiConfig.SESSION_SCHEME)
    @GetMapping("/v1/sessions/{id}/record")
    public RecordResponse getRecord(
            @PathVariable("id") String sessionId,
            @CookieValue(name = "${session.cookie-name}", required = false) String sessionCookie
    ) {
        return sessionService.getRecord(sessionId, sessionCookie);
    }

    @Operation(summary = "Upload a supplementary document",
            description = "Multipart upload for proof of address, power of attorney, etc. Returns a reference to include in the next interaction submission. "
                    + "Document/selfie capture stays a placeholder pending a capture SDK choice — see the front-end handoff, section 6.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attachment reference"),
            @ApiResponse(responseCode = "410", description = "Session missing, expired, or cookie mismatch",
                    content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @SecurityRequirement(name = OpenApiConfig.SESSION_SCHEME)
    @PostMapping("/v1/sessions/{id}/attachments")
    public AttachmentResponse uploadAttachment(
            @PathVariable("id") String sessionId,
            @RequestPart("file") MultipartFile file,
            @CookieValue(name = "${session.cookie-name}", required = false) String sessionCookie
    ) {
        try {
            return sessionService.uploadAttachment(sessionId, sessionCookie, file.getBytes());
        } catch (java.io.IOException e) {
            throw OnboardingException.validationFailed("That image could not be read. Try again.", null);
        }
    }

    @Operation(summary = "Per-app presentation configuration",
            description = "Brand name, accent colour, help contact, journey name and resource ID — keeps brand values out of the front-end bundle so a deployment can be re-pointed without a rebuild.")
    @ApiResponse(responseCode = "200", description = "This deployment's configuration")
    @GetMapping("/v1/config")
    public AppConfigResponse getConfig() {
        return sessionService.getConfig();
    }

    // --- cookie plumbing -----------------------------------------------

    /**
     * {@code secure} tracks the incoming request's own scheme rather than
     * being hardcoded true: a browser's "localhost is a secure context"
     * exception is Chromium-specific, and a `Secure` cookie issued over
     * plain HTTP is silently dropped by curl, PowerShell, and most other
     * HTTP clients — found by testing this directly, not by inspection.
     * Behind real HTTPS in any other environment this is still `Secure`.
     *
     * <p>No {@code Max-Age}: a browser-session cookie, with expiry left to the
     * server-side TTL, which slides on every call. A fixed Max-Age set at
     * start ran out 30 minutes later however active the customer was, and
     * they got "session ended" while their session was still alive.
     */
    private void setSessionCookie(HttpServletResponse response, String token, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(sessionProperties.cookieName(), token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/v1/sessions")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
