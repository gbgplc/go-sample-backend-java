package com.gbg.samples.onboarding.session;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.SessionProperties;
import com.gbg.samples.onboarding.go.GoClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review finding 18: with no interaction on record, the stale check falls back to Go's current one. */
class SessionServiceTest {

    private final GoClient go = mock(GoClient.class);
    private final InMemorySessionStore store = new InMemorySessionStore(new SessionProperties(30, "onboarding_session"));
    private final SessionService service = new SessionService(go, store, mock(AppConfigProperties.class));

    private static Interaction form(String interactionId) {
        return new Interaction(interactionId, ScreenKind.FORM, "About you", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A session started with no interaction on record — e.g. Go had nothing to show yet. */
    private void sessionWithNoInteractionOnRecord() {
        store.save(new Session("s1", "cookie", "instance-1", null));
    }

    @Test
    void anyIdIsStaleWhenGoHasNoCurrentInteractionEither() {
        sessionWithNoInteractionOnRecord();
        when(go.fetchInteraction("instance-1")).thenReturn(new SubmitInteractionResponse(JourneyStatus.IN_PROGRESS, null));

        OnboardingException ex = catchThrowableOfType(
                () -> service.submitInteraction("s1", "cookie", "made-up", Map.of()), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.INTERACTION_STALE);
        verify(go, never()).submitInteraction(anyString(), anyString(), anyMap());
    }

    @Test
    void anIdOtherThanGosCurrentOneIsStale() {
        sessionWithNoInteractionOnRecord();
        when(go.fetchInteraction("instance-1"))
                .thenReturn(new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, form("int-9")));

        OnboardingException ex = catchThrowableOfType(
                () -> service.submitInteraction("s1", "cookie", "int-1", Map.of()), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.INTERACTION_STALE);
    }

    @Test
    void gosCurrentIdIsForwarded() {
        sessionWithNoInteractionOnRecord();
        when(go.fetchInteraction("instance-1"))
                .thenReturn(new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, form("int-9")));
        when(go.submitInteraction(anyString(), anyString(), any()))
                .thenReturn(new SubmitInteractionResponse(JourneyStatus.PENDING_INPUT, form("int-9")));

        service.submitInteraction("s1", "cookie", "int-9", Map.of("MothersMaidenName", "Smith"));

        verify(go).submitInteraction("instance-1", "int-9", Map.of("MothersMaidenName", "Smith"));
    }
}
