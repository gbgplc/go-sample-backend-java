package com.gbg.samples.onboarding.go.sdk;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.GoSdkProperties;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GoSdkClient through the real SDK against {@link FakeGo}: the client half of
 * the stale-Side2Required fix (which image slot each capture is sent to as
 * the journey moves through the front, the back and the selfie), and how a
 * refused journey/start is reported. The mapper half is in
 * SdkInteractionMapperTest.
 */
class GoSdkClientSide2Test {

    private static final String FETCH = "/journey/interaction/fetch";
    private static final String SUBMIT = "/journey/interaction/submit";
    private static final String START = "/journey/start";
    private static final String SUBMIT_OK = "{\"status\":\"success\"}";

    private static final ScreenPlanProperties.Stage DOCUMENT_STAGE = new ScreenPlanProperties.Stage(
            "document", ScreenKind.CAPTURE, "PrimaryDocument/", "Document",
            "Scan your photo ID", "We check the document is genuine.", "Scan document", "document",
            List.of("Passport"), List.of("Document Classification"), null, null);

    private static final ScreenPlanProperties.Stage BIOMETRICS_STAGE = new ScreenPlanProperties.Stage(
            "biometrics", ScreenKind.CAPTURE, "Selfie/", "Biometrics",
            "Take a selfie", "This proves you are the person in the document.", "Take selfie", "selfie",
            null, List.of("Liveness Verification"), null, null);

    private FakeGo go;
    private GoSdkClient client;

    @BeforeEach
    void setUp() throws IOException {
        go = new FakeGo();
        GoSdkProperties properties = new GoSdkProperties("live", "eu", "https://auth.test/as/token.oauth2", "gbg.token",
                "client_credentials", "id", "secret", null, null, go.url("/v2/captain/"));
        GoSdkAuthService tokens = mock(GoSdkAuthService.class);
        when(tokens.accessToken()).thenReturn("good");
        client = new GoSdkClient(properties, tokens,
                new SdkInteractionMapper(new ScreenPlanProperties(List.of(DOCUMENT_STAGE, BIOMETRICS_STAGE), List.of())),
                mock(AppConfigProperties.class));
    }

    @AfterEach
    void tearDown() {
        go.close();
    }

    private void fetch(List<String> outstanding, List<String> instructions) {
        go.reply(FETCH, 200, "{\"view\":\"full\",\"instanceId\":\"i-1\",\"interactionId\":\"int-1\","
                + "\"journey\":{\"status\":\"InProgress\"},\"interaction\":{\"id\":\"int-1\"},"
                + "\"outstanding\":" + json(outstanding) + ",\"instructions\":" + json(instructions) + "}");
    }

    private static String json(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    /** Go's side of the front: it reads side 1 and asks for the back. */
    private void goReadsTheFront() {
        fetch(List.of("PrimaryDocument/side1Image", "Selfie/selfieImage"), List.of("LazySide2CollectionRequired"));
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of("PrimaryDocument/side2Image", "Selfie/selfieImage"), List.of("Side2Required"));
    }

    private Interaction submitTheFront() {
        client.fetchInteraction("i-1");
        return capture("front-bytes");
    }

    private Interaction capture(String image) {
        return client.submitInteraction("i-1", "int-1", Map.of("attachmentRef", image)).interaction();
    }

    /** The submit bodies Go received, in order. */
    private List<String> submits() {
        return go.bodiesSentTo(SUBMIT);
    }

    /**
     * The sequence seen live (2026-11-02, Meridian): the fetch right after
     * the back still says Side2Required with side2Image gone from outstanding.
     * The screen moves on to the selfie, and the capture taken on it goes to
     * Go as the selfie, not as a second back.
     */
    @Test
    void afterTheBackTheNextCaptureIsTheSelfieEvenWhileGoStillSaysSide2Required() {
        goReadsTheFront();
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of(), List.of("Side2Done"));

        assertThat(submitTheFront().captureType()).isEqualTo("document-back");
        assertThat(capture("back-bytes").stage()).isEqualTo("Biometrics");
        capture("selfie-bytes");

        assertThat(submits()).hasSize(3);
        assertThat(submits().get(0)).contains("side1Image", "front-bytes").doesNotContain("side2Image");
        assertThat(submits().get(1)).contains("side2Image", "back-bytes").doesNotContain("selfieImage");
        assertThat(submits().get(2)).contains("selfieImage", "selfie-bytes").doesNotContain("side2Image");
    }

    /** A back Go refuses records nothing, so the retry still goes to the back. */
    @Test
    void aFailedBackSubmitLeavesTheBackOpen() {
        goReadsTheFront();
        go.reply(SUBMIT, 500, null);
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Done"));

        submitTheFront();
        catchThrowableOfType(() -> capture("back-bytes"), OnboardingException.class);
        assertThat(client.fetchInteraction("i-1").interaction().captureType()).isEqualTo("document-back");
        capture("back-bytes");

        assertThat(submits()).hasSize(3);
        assertThat(submits().get(2)).contains("side2Image", "back-bytes").doesNotContain("selfieImage");
    }

    /**
     * Some journeys list side2Image before the front is captured. The screen
     * is the front, so the capture is sent as the front, not as the back.
     */
    @Test
    void aSide2ImageListedBeforeTheFrontLeavesTheCaptureAsTheFront() {
        fetch(List.of("PrimaryDocument/side1Image", "PrimaryDocument/side2Image", "Selfie/selfieImage"),
                List.of("Side2Required"));
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of("PrimaryDocument/side2Image", "Selfie/selfieImage"), List.of("Side2Required"));

        assertThat(client.fetchInteraction("i-1").interaction().stage()).isEqualTo("Document");
        assertThat(capture("front-bytes").captureType()).isEqualTo("document-back");

        assertThat(submits()).singleElement().asString().contains("side1Image").doesNotContain("side2Image");
    }

    /**
     * Once Go says Side2Done the back-submitted marker is dropped, so a later
     * request for the back that comes as the instruction alone reopens it.
     */
    @Test
    void aRequestForTheBackAfterSide2DoneReopensIt() {
        goReadsTheFront();
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Done"));
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Required"));
        go.reply(SUBMIT, 200, SUBMIT_OK);
        fetch(List.of("Selfie/selfieImage"), List.of("Side2Done"));

        submitTheFront();
        assertThat(capture("back-bytes").stage()).isEqualTo("Biometrics");
        assertThat(client.fetchInteraction("i-1").interaction().stage()).isEqualTo("Biometrics");
        assertThat(client.fetchInteraction("i-1").interaction().captureType()).isEqualTo("document-back");
        capture("second-back-bytes");

        assertThat(submits().get(2)).contains("side2Image", "second-back-bytes").doesNotContain("selfieImage");
    }

    /**
     * A journey Go doesn't have (live, gbggo4-demo: 404 "Delivery not found")
     * is a configuration fault, not a session that ended: no "Start again".
     */
    @Test
    void aStartGoRefusesWith404IsMisconfiguredNotSessionExpired() {
        go.reply(START, 404,
                "{\"errors\":[{\"code\":\"1001\",\"name\":\"NOT_FOUND\",\"problem\":\"Delivery not found\"}]}");

        OnboardingException ex = catchThrowableOfType(
                () -> client.startJourney("missing@journey", Map.of(), null), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(ex.retryable()).isFalse();
        assertThat(ex.getMessage()).isEqualTo("Could not start your verification.");
    }
}
