package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    private static final SubmitInteractionResponse SOME_RESPONSE =
            new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, null);

    @Test
    void isNotARetryBeforeAnythingHasBeenSubmitted() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");

        assertThat(session.isRetryOf("int-1", Map.of())).isFalse();
    }

    @Test
    void sameInteractionIdAndSamePayloadIsARetry() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", Map.of("field", "value"), SOME_RESPONSE);

        assertThat(session.isRetryOf("int-1", Map.of("field", "value"))).isTrue();
        assertThat(session.cachedResponse()).isSameAs(SOME_RESPONSE);
    }

    @Test
    void sameInteractionIdWithDifferentPayloadIsTheNextStepNotARetry() {
        // Go reuses one interactionId across an entire collection phase, so the id
        // alone can't tell a retry from a genuine advance — the payload must differ.
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", Map.of("field", "value"), SOME_RESPONSE);

        assertThat(session.isRetryOf("int-1", Map.of("field", "a different value"))).isFalse();
    }

    @Test
    void differentInteractionIdIsNeverARetry() {
        Session session = new Session("s1", "cookie", "instance-1", "int-1");
        session.recordAdvance("int-1", Map.of("field", "value"), SOME_RESPONSE);

        assertThat(session.isRetryOf("int-2", Map.of("field", "value"))).isFalse();
    }
}
