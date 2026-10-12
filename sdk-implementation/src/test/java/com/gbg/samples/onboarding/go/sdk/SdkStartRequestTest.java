package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.operations.StartJourneyRequest;
import com.gbg.gocore.models.operations.StartJourneySubject;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The prefill is sent as {@code context.subject}, as api-implementation sends it, through the SDK's typed subject. */
class SdkStartRequestTest {

    private final SdkInteractionMapper mapper =
            new SdkInteractionMapper(new ScreenPlanProperties(List.of(), List.of()));

    private static StartJourneySubject subjectOf(StartJourneyRequest request) {
        return request.context().subject().orElse(null);
    }

    @Test
    void aSubjectShapedPrefillIsSentAsTheTypedSubject() {
        StartJourneyRequest request = mapper.toStartRequest("journey@v1", Map.of(
                "identity", Map.of("firstName", "Ada", "lastNames", List.of("Lovelace"), "dateOfBirth", "1815-12-10")));

        StartJourneySubject subject = subjectOf(request);
        assertThat(request.resourceId()).isEqualTo("journey@v1");
        assertThat(request.context().config().delivery()).isEqualTo("api");
        assertThat(subject).isNotNull();
        assertThat(subject.identity().orElseThrow().firstName()).contains("Ada");
        assertThat(subject.identity().orElseThrow().lastNames()).contains(List.of("Lovelace"));
        assertThat(subject.identity().orElseThrow().dateOfBirth()).contains("1815-12-10");
    }

    @Test
    void aKeyTheSubjectHasNoFieldForIsDroppedButTheRestIsSent() {
        StartJourneyRequest request = mapper.toStartRequest("journey@v1", Map.of(
                "identity", Map.of("firstName", "Ada"),
                "loyaltyTier", "gold"));

        assertThat(subjectOf(request).identity().orElseThrow().firstName()).contains("Ada");
    }

    @Test
    void aPrefillThatDoesNotFitTheTypeStartsTheJourneyWithoutOne() {
        StartJourneyRequest request = mapper.toStartRequest("journey@v1", Map.of("identity", "not an object"));

        assertThat(subjectOf(request)).isNull();
    }

    @Test
    void noPrefillMeansNoSubject() {
        assertThat(subjectOf(mapper.toStartRequest("journey@v1", null))).isNull();
        assertThat(subjectOf(mapper.toStartRequest("journey@v1", Map.of()))).isNull();
    }
}
