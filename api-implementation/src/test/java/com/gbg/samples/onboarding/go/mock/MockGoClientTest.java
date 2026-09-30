package com.gbg.samples.onboarding.go.mock;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.ScreenKind;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.go.GoStartResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review finding 20: a choice value matching no option is rejected, not silently advanced past. */
class MockGoClientTest {

    private MockGoClient meridian() {
        AppConfigProperties appConfig = mock(AppConfigProperties.class);
        when(appConfig.market()).thenReturn("meridian-health");
        return new MockGoClient(new FixtureCatalog(appConfig));
    }

    /** Meridian's script opens on an intro screen, then asks who the record is for. */
    private static Interaction toFirstChoice(MockGoClient client, GoStartResult started) {
        Interaction choice = client.submitInteraction(started.instanceId(), started.interaction().interactionId(), Map.of())
                .interaction();
        assertThat(choice.kind()).isEqualTo(ScreenKind.CHOICE);
        return choice;
    }

    @Test
    void aChoiceMatchingNoOptionIsRejected() {
        MockGoClient client = meridian();
        GoStartResult started = client.startJourney("resource", Map.of(), null);
        Interaction choice = toFirstChoice(client, started);

        OnboardingException ex = catchThrowableOfType(() -> client.submitInteraction(
                started.instanceId(), choice.interactionId(), Map.of("value", "not-an-option")), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(client.fetchInteraction(started.instanceId()).interaction().interactionId())
                .isEqualTo(choice.interactionId());
    }

    @Test
    void aRealOptionStillAdvances() {
        MockGoClient client = meridian();
        GoStartResult started = client.startJourney("resource", Map.of(), null);
        Interaction choice = toFirstChoice(client, started);

        Interaction next = client.submitInteraction(started.instanceId(), choice.interactionId(),
                Map.of("value", choice.options().get(0).value())).interaction();

        assertThat(next.interactionId()).isNotEqualTo(choice.interactionId());
    }
}
