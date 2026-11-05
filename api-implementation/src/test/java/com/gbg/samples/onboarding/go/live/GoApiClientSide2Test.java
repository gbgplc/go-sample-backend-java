package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.GoProperties;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The client half of the stale-Side2Required fix, against a stubbed Go: which
 * image slot each capture is sent to as the journey moves through the front,
 * the back and the selfie. The mapper half is in DefaultInteractionMapperTest.
 *
 * Each test declares Go's whole conversation up front (MockRestServiceServer
 * takes no expectations once requests have started), then drives the client.
 */
class GoApiClientSide2Test {

    private static final String BASE = "https://go.test/v2/captain/";
    private static final String SUBMIT_OK = "{\"status\":\"success\"}";

    private static final ScreenPlanProperties.Stage DOCUMENT_STAGE = new ScreenPlanProperties.Stage(
            "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
            "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
            List.of("Passport"), List.of("Document Classification"), null, null);

    private static final ScreenPlanProperties.Stage BIOMETRICS_STAGE = new ScreenPlanProperties.Stage(
            "biometrics", ScreenKind.CAPTURE, "Selfie/", "Biometrics",
            "Take a selfie", "This proves you are the person in the document.", "Take selfie", "selfie",
            null, List.of("Liveness Verification"), null, null);

    private final GoTokenService tokens = mock(GoTokenService.class);
    private MockRestServiceServer go;
    private GoApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        go = MockRestServiceServer.bindTo(builder).build();
        GoProperties properties = new GoProperties("live", "eu", null, null, null, "id", "secret", null, null, BASE, null);
        client = new GoApiClient(properties, tokens,
                new DefaultInteractionMapper(new ScreenPlanProperties(List.of(DOCUMENT_STAGE, BIOMETRICS_STAGE), List.of())),
                mock(AppConfigProperties.class), builder);
        when(tokens.accessToken()).thenReturn("good");
    }

    private void expectFetch(List<String> outstanding, List<String> instructions) {
        String body = "{\"instanceId\":\"i-1\",\"journey\":{\"status\":\"InProgress\"},\"outstanding\":"
                + json(outstanding) + ",\"instructions\":" + json(instructions) + "}";
        go.expect(requestTo(BASE + "journey/interaction/fetch")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String json(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    /** A submit carrying {@code image} in {@code slot}, and nothing in {@code notIn}. */
    private void expectSubmit(String image, String slot, String notIn) {
        go.expect(requestTo(BASE + "journey/interaction/submit"))
                .andExpect(content().string(allOf(containsString(image), containsString(slot), not(containsString(notIn)))))
                .andRespond(withSuccess(SUBMIT_OK, MediaType.APPLICATION_JSON));
    }

    /** Go's side of the front: it reads side 1 and asks for the back. */
    private void expectTheFront() {
        expectFetch(List.of("PrimaryDocument/side1Image", "Selfie/selfieImage"), List.of("LazySide2CollectionRequired"));
        expectSubmit("front-bytes", "side1Image", "side2Image");
        expectFetch(List.of("PrimaryDocument/side2Image", "Selfie/selfieImage"), List.of("Side2Required"));
    }

    private Interaction submitTheFront() {
        client.fetchInteraction("i-1");
        return capture("front-bytes");
    }

    private Interaction capture(String image) {
        return client.submitInteraction("i-1", "int-1", Map.of("attachmentRef", image)).interaction();
    }

    /**
     * The sequence seen live (2026-11-02, Meridian): the fetch right after
     * the back still says Side2Required with side2Image gone from outstanding.
     * The screen moves on to the selfie, and the capture taken on it goes to
     * Go as the selfie, not as a second back.
     */
    @Test
    void afterTheBackTheNextCaptureIsTheSelfieEvenWhileGoStillSaysSide2Required() {
        expectTheFront();
        expectSubmit("back-bytes", "side2Image", "selfieImage");
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        expectSubmit("selfie-bytes", "\"biometrics\"", "side2Image");
        expectFetch(List.of(), List.of("Side2Done"));

        assertThat(submitTheFront().captureType()).isEqualTo("document-back");
        assertThat(capture("back-bytes").stage()).isEqualTo("Biometrics");
        capture("selfie-bytes");

        go.verify();
    }

    /** A back Go refuses records nothing, so the retry still goes to the back. */
    @Test
    void aFailedBackSubmitLeavesTheBackOpen() {
        expectTheFront();
        go.expect(requestTo(BASE + "journey/interaction/submit")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        expectSubmit("back-bytes", "side2Image", "selfieImage");
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Done"));

        submitTheFront();
        catchThrowableOfType(() -> capture("back-bytes"), OnboardingException.class);
        assertThat(client.fetchInteraction("i-1").interaction().captureType()).isEqualTo("document-back");
        capture("back-bytes");

        go.verify();
    }

    /**
     * Some journeys list side2Image before the front is captured. The screen
     * is the front, so the capture is sent as the front, not as the back.
     */
    @Test
    void aSide2ImageListedBeforeTheFrontLeavesTheCaptureAsTheFront() {
        expectFetch(List.of("PrimaryDocument/side1Image", "PrimaryDocument/side2Image", "Selfie/selfieImage"),
                List.of("Side2Required"));
        expectSubmit("front-bytes", "side1Image", "side2Image");
        expectFetch(List.of("PrimaryDocument/side2Image", "Selfie/selfieImage"), List.of("Side2Required"));

        assertThat(client.fetchInteraction("i-1").interaction().stage()).isEqualTo("Document");
        assertThat(capture("front-bytes").captureType()).isEqualTo("document-back");

        go.verify();
    }

    /**
     * Once Go says Side2Done the back-submitted marker is dropped, so a later
     * request for the back that comes as the instruction alone reopens it.
     */
    @Test
    void aRequestForTheBackAfterSide2DoneReopensIt() {
        expectTheFront();
        expectSubmit("back-bytes", "side2Image", "selfieImage");
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Done"));
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        expectSubmit("second-back-bytes", "side2Image", "selfieImage");
        expectFetch(List.of("Selfie/selfieImage"), List.of("Side2Done"));

        submitTheFront();
        assertThat(capture("back-bytes").stage()).isEqualTo("Biometrics");
        assertThat(client.fetchInteraction("i-1").interaction().stage()).isEqualTo("Biometrics");
        assertThat(client.fetchInteraction("i-1").interaction().captureType()).isEqualTo("document-back");
        capture("second-back-bytes");

        go.verify();
    }
}
