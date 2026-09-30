package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    private static final SubmitInteractionResponse SOME_RESPONSE =
            new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, null);

    /**
     * A live-shaped response: the resulting interaction carries the same
     * interactionId as the one just submitted, exactly like Go reusing
     * {@code segment1@latest} across an entire collection phase — this is
     * what makes {@link Session#isRetryOf} switch to comparing stage.
     */
    private static SubmitInteractionResponse reusingInteractionId(String interactionId) {
        return new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, interactionOf(interactionId));
    }

    /**
     * A mock-shaped response: the resulting interaction carries a new,
     * different interactionId, exactly like the mock's per-step ids — this
     * is what makes {@link Session#isRetryOf} fall back to comparing payload.
     */
    private static SubmitInteractionResponse advancingToNewInteractionId(String nextInteractionId) {
        return new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, interactionOf(nextInteractionId));
    }

    private static Interaction interactionOf(String interactionId) {
        return new Interaction(interactionId, ScreenKind.FORM, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void isNotARetryBeforeAnythingHasBeenSubmitted() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");

        assertThat(session.isRetryOf("int-1", "About you", Map.of())).isFalse();
    }

    @Test
    void sameInteractionIdAndSameStageIsARetry() {
        // Live shape: the interactionId is reused, so stage is what disambiguates.
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        SubmitInteractionResponse response = reusingInteractionId("int-1");
        session.recordAdvance("int-1", "About you", Map.of("field", "value"), response);

        assertThat(session.isRetryOf("int-1", "About you", Map.of("field", "value"))).isTrue();
        assertThat(session.cachedResponse()).isSameAs(response);
    }

    @Test
    void sameInteractionIdWithDifferentStageIsTheNextStepNotARetry() {
        // Go reuses one interactionId across an entire collection phase, so the id
        // alone can't tell a retry from a genuine advance — the stage must differ.
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", "About you", Map.of("field", "value"), reusingInteractionId("int-1"));

        assertThat(session.isRetryOf("int-1", "Contact details", Map.of("field", "a different value"))).isFalse();
    }

    /**
     * The actual reported bug: two different, data-less screens (e.g. an
     * info screen then a consent screen) both post {@code {}} against the
     * same live interactionId. Comparing payload alone (the previous
     * implementation) called the second one a retry of the first and served
     * it the first screen's cached response instead of forwarding it —
     * comparing stage tells them apart correctly even though the payload is
     * identical on both.
     */
    @Test
    void sameInteractionIdAndSameEmptyPayloadButDifferentStageIsNotARetry() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", "Info", Map.of(), reusingInteractionId("int-1"));

        assertThat(session.isRetryOf("int-1", "Consent", Map.of())).isFalse();
    }

    /**
     * The front and back of a two-sided document share one stage by design,
     * against the same live interactionId. Stage alone called the back a
     * retry of the front and served it from cache, so Go never received the
     * back and the screen looped. The different image is what tells them apart.
     */
    @Test
    void documentBackAfterFrontIsNotARetryDespiteSharingTheStage() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", "Document", Map.of("attachmentRef", "front-image"), reusingInteractionId("int-1"));

        assertThat(session.isRetryOf("int-1", "Document", Map.of("attachmentRef", "back-image"))).isFalse();
    }

    /**
     * The mock (and anything else whose interactionId changes per step)
     * must stay idempotent for an exact resubmission of the step that was
     * just completed, even though {@code currentInteractionId} has already
     * moved on to the next step's different id by the time this runs — this
     * is what regressed when stage comparison was first introduced (it
     * always disagrees once the id has moved on, since a freshly-read stage
     * can never equal the pre-advance stage that was recorded).
     */
    @Test
    void immediateResubmissionAfterTheIdAdvancesIsStillARetry() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", "Start", Map.of(), advancingToNewInteractionId("int-2"));

        assertThat(session.isRetryOf("int-1", "Details", Map.of())).isTrue();
    }

    @Test
    void differentInteractionIdIsNeverARetry() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", "About you", Map.of("field", "value"), SOME_RESPONSE);

        assertThat(session.isRetryOf("int-2", "About you", Map.of("field", "value"))).isFalse();
    }

    @Test
    void fallsBackToPayloadComparisonWhenStageIsUnset() {
        // A mock fixture (or any caller) that never supplies a stage — the
        // original payload-based guard still applies as a safety net.
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", null, Map.of("field", "value"), SOME_RESPONSE);

        assertThat(session.isRetryOf("int-1", null, Map.of("field", "value"))).isTrue();
        assertThat(session.isRetryOf("int-1", null, Map.of("field", "different"))).isFalse();
    }
}
