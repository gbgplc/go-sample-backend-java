package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.SummaryRow;
import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.config.CallsGoCondition;
import com.gbg.samples.onboarding.config.GoProperties;
import com.gbg.samples.onboarding.config.ScreenPlanProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * go.mode=test-scenarios: journeys start on a named GBG Go test scenario,
 * picked by the front end's ?mock_scenario= or the market's go.test-scenario.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoApiClientTestScenariosTest {

    private static final String BASE = "https://go.test/v2/captain/";
    private static final String TEST_START =
            "{\"instanceId\":\"test_abc\",\"status\":\"started\",\"test\":true,\"scenario\":\"northbank-pass\"}";
    private static final String PROCESSING_FETCH =
            "{\"instanceId\":\"test_abc\",\"journey\":{\"status\":\"InProgress\"},\"processing\":true,\"outstanding\":[]}";

    private final GoTokenService tokens = mock(GoTokenService.class);
    private MockRestServiceServer go;

    private GoApiClient client(String mode, String defaultScenario) {
        when(tokens.accessToken()).thenReturn("token");
        RestClient.Builder builder = RestClient.builder();
        go = MockRestServiceServer.bindTo(builder).build();
        GoProperties properties = new GoProperties(mode, "eu", null, null, null, "id", "secret", null, null, BASE,
                defaultScenario);
        return new GoApiClient(properties, tokens,
                new DefaultInteractionMapper(new ScreenPlanProperties(List.of(), List.of())),
                mock(AppConfigProperties.class), builder);
    }

    @Test
    void liveModeSendsNoScenarioHeader() {
        GoApiClient client = client("live", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andExpect(headerDoesNotExist(GoApiClient.SCENARIO_HEADER))
                .andRespond(withSuccess("{\"instanceId\":\"i-1\",\"status\":\"started\"}", MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andRespond(withSuccess(PROCESSING_FETCH.replace("test_abc", "i-1"), MediaType.APPLICATION_JSON));

        client.startJourney("journey@v1", null, "northbank-refer");

        go.verify();
    }

    @Test
    void startsOnTheMarketsDefaultScenarioWhenTheFrontEndNamesNone() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andExpect(header(GoApiClient.SCENARIO_HEADER, "northbank-pass"))
                .andRespond(withSuccess(TEST_START, MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andExpect(headerDoesNotExist(GoApiClient.SCENARIO_HEADER))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));

        assertThat(client.startJourney("journey@v1", null, null).instanceId()).isEqualTo("test_abc");
        go.verify();
    }

    @Test
    void theFrontEndsMockScenarioPicksTheScenario() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andExpect(header(GoApiClient.SCENARIO_HEADER, "northbank-refer"))
                .andRespond(withSuccess(TEST_START, MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));

        client.startJourney("journey@v1", null, "northbank-refer");

        go.verify();
    }

    /** Falling back would run the default (pass) scenario when the tester asked for another. */
    @Test
    void anInvalidScenarioNameFromTheFrontEndIsRefusedWithoutCallingGo() {
        GoApiClient client = client("test-scenarios", "northbank-pass");

        for (String hint : List.of("bad name\r\nx-injected: 1", "northbank refer", "x".repeat(65))) {
            OnboardingException ex = catchThrowableOfType(
                    () -> client.startJourney("journey@v1", null, hint), OnboardingException.class);
            assertThat(ex.code()).as(hint).isEqualTo(ErrorCode.BAD_REQUEST);
        }
        go.verify();
    }

    @Test
    void surroundingWhitespaceOnTheFrontEndsScenarioIsTrimmed() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andExpect(header(GoApiClient.SCENARIO_HEADER, "northbank-refer"))
                .andRespond(withSuccess(TEST_START, MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));

        client.startJourney("journey@v1", null, " northbank-refer ");

        go.verify();
    }

    @Test
    void noScenarioAtAllFailsWithoutCallingGo() {
        GoApiClient client = client("test-scenarios", null);

        OnboardingException ex = catchThrowableOfType(
                () -> client.startJourney("journey@v1", null, null), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        go.verify();
    }

    @Test
    void aJourneyGoDidNotStartAsATestRunGoesNoFurther() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start"))
                .andRespond(withSuccess("{\"instanceId\":\"i-live\",\"status\":\"started\"}", MediaType.APPLICATION_JSON));

        OnboardingException ex = catchThrowableOfType(
                () -> client.startJourney("journey@v1", null, null), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        // No interaction fetch, so nothing further happens on the live instance.
        go.verify();
    }

    /** Go's explicit flag wins over the instance-ID prefix. */
    @Test
    void anExplicitTestFalseStopsTheJourneyEvenWithATestPrefix() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andRespond(withSuccess(
                "{\"instanceId\":\"test_abc\",\"status\":\"started\",\"test\":false}", MediaType.APPLICATION_JSON));

        OnboardingException ex = catchThrowableOfType(
                () -> client.startJourney("journey@v1", null, null), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        go.verify();
    }

    @Test
    void aStartWithoutTheTestFlagStillCountsOnTheTestPrefix() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andRespond(withSuccess(
                "{\"instanceId\":\"test_abc\",\"status\":\"started\"}", MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));

        assertThat(client.startJourney("journey@v1", null, null).instanceId()).isEqualTo("test_abc");
        go.verify();
    }

    /** Otherwise call() maps it to a field validation error on a screen the customer hasn't filled in. */
    @Test
    void aBadRequestAtStartIsNotReportedAsAFormError() {
        GoApiClient client = client("test-scenarios", "northbank-missing");
        go.expect(requestTo(BASE + "journey/start")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"code\":\"400\",\"name\":\"BadRequest\",\"problem\":\"unknown scenario\",\"action\":\"\"}]}"));

        OnboardingException ex = catchThrowableOfType(
                () -> client.startJourney("journey@v1", null, null), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void aPersistent403SuggestsTestScenariosAreNotEnabled(CapturedOutput output) {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        // call() mints a fresh token and retries once on a 403.
        go.expect(times(2), requestTo(BASE + "journey/start")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.startJourney("journey@v1", null, null)).isInstanceOf(OnboardingException.class);

        assertThat(output).contains("may not be enabled for this organisation");
    }

    @Test
    void otherStartFailuresDoNotBlameTheFeature(CapturedOutput output) {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.startJourney("journey@v1", null, null)).isInstanceOf(OnboardingException.class);

        assertThat(output).doesNotContain("may not be enabled");
    }

    @Test
    void aScenarioGoCannotRunIsNotReportedAsAnEndedSession() {
        GoApiClient client = client("test-scenarios", "northbank-missing");
        go.expect(requestTo(BASE + "journey/start")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"code\":\"404\",\"name\":\"NotFound\",\"problem\":\"scenario not found\",\"action\":\"\"}]}"));

        OnboardingException ex = catchThrowableOfType(
                () -> client.startJourney("journey@v1", null, null), OnboardingException.class);

        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void aTestRunsRecordSaysSoWithItsScenario() {
        GoApiClient client = client("test-scenarios", "northbank-pass");
        go.expect(requestTo(BASE + "journey/start")).andRespond(withSuccess(TEST_START, MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/interaction/fetch"))
                .andRespond(withSuccess(PROCESSING_FETCH, MediaType.APPLICATION_JSON));
        go.expect(requestTo(BASE + "journey/state/fetch")).andRespond(withSuccess(
                "{\"instanceId\":\"test_abc\",\"status\":\"Completed\",\"result\":{\"status\":\"complete\","
                        + "\"outcomeClassification\":\"positive\"}}", MediaType.APPLICATION_JSON));

        client.startJourney("journey@v1", null, null);
        RecordResponse record = client.fetchRecord("test_abc");

        assertThat(record.summary().get(0)).isEqualTo(new SummaryRow("Test run", "Yes, scenario northbank-pass"));
    }

    @Test
    void aLiveRunsRecordHasNoTestRow() {
        GoApiClient client = client("live", null);
        go.expect(requestTo(BASE + "journey/state/fetch")).andRespond(withSuccess(
                "{\"instanceId\":\"i-1\",\"status\":\"Completed\",\"result\":{\"outcomeClassification\":\"positive\"}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.fetchRecord("i-1").summary()).noneMatch(row -> row.k().equals("Test run"));
    }

    @Test
    void anInvalidDefaultScenarioFailsAtStartup() {
        assertThatThrownBy(() -> client("test-scenarios", "not a valid name"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("go.test-scenario");
    }

    @Test
    void liveAndTestScenariosBothCallGo() {
        assertThat(List.of("live", "test-scenarios", "LIVE", "Test-Scenarios")).allMatch(GoProperties::callsGo);
        assertThat(java.util.Arrays.asList("mock", "test-scenario", "", null)).noneMatch(GoProperties::callsGo);
    }

    /** Stands in for GoApiClient and GoTokenService, which carry the same condition. */
    @Configuration
    @Conditional(CallsGoCondition.class)
    static class CallsGo {
    }

    @Test
    void liveAndTestScenariosModesBothWireInTheRealGoClient() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CallsGo.class);

        runner.withPropertyValues("go.mode=live").run(context -> assertThat(context).hasSingleBean(CallsGo.class));
        runner.withPropertyValues("go.mode=test-scenarios").run(context -> assertThat(context).hasSingleBean(CallsGo.class));
        runner.withPropertyValues("go.mode=Test-Scenarios").run(context -> assertThat(context).hasSingleBean(CallsGo.class));
        runner.withPropertyValues("go.mode=mock").run(context -> assertThat(context).doesNotHaveBean(CallsGo.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(CallsGo.class));
    }
}
