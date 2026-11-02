package com.gbg.samples.onboarding.go.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.gbg.gocore.models.InteractionFetchResponse;
import com.gbg.gocore.models.SlimInteractionFetchResponse;
import com.gbg.gocore.models.StateFetchResponse;
import com.gbg.gocore.models.operations.FetchInteractionResponseBody;
import com.gbg.gocore.models.operations.GetJourneyStateResponseBody;
import com.gbg.gocore.models.operations.UnknownFetchInteractionResponseBody;
import com.gbg.gocore.models.operations.UnknownGetJourneyStateResponseBody;
import com.gbg.gocore.utils.JSON;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * go-core-sdk 0.2.0-alpha02 picks the fetch and state response variant by
 * the response's {@code view} property. alpha01 picked it by which variant's
 * fields best matched the JSON, so a full-view fetch (which has no
 * {@code context}) came back as the slim variant.
 *
 * The API reference marks {@code view} as required; a response without it
 * becomes the SDK's Unknown variant, which {@link GoSdkClient#asFullView}
 * reads as the full view.
 */
class FetchResponseVariantTest {

    private static final String FETCH_FIELDS = """
              "instanceId": "inst-1",
              "interactionId": "grn:::gbg:design:interaction:segment1@latest",
              "journey": {"status": "InProgress"},
              "interaction": {"collects": [{"ref": "PrimaryDocument/side1Image", "spec": "required"}]},
              "outstanding": ["PrimaryDocument/side1Image"],
              "instructions": ["LazySide2CollectionRequired"],
              "processing": false
            """;

    private static FetchInteractionResponseBody fetch(String view) throws Exception {
        String viewField = view == null ? "" : "\"view\": \"" + view + "\",";
        return JSON.getMapper().readValue("{" + viewField + FETCH_FIELDS + "}", FetchInteractionResponseBody.class);
    }

    private static void assertCarriesWhatTheMapperReads(InteractionFetchResponse full) {
        assertThat(full.instanceId()).isEqualTo("inst-1");
        assertThat(full.interactionId()).contains("grn:::gbg:design:interaction:segment1@latest");
        assertThat(full.journey().status()).isEqualTo("InProgress");
        assertThat(SdkInteractionMapper.collectableRefs(full)).containsExactly("PrimaryDocument/side1Image");
        assertThat(SdkInteractionMapper.stringList(full.outstanding().orElse(null))).containsExactly("PrimaryDocument/side1Image");
        assertThat(SdkInteractionMapper.stringList(full.instructions().orElse(null))).containsExactly("LazySide2CollectionRequired");
    }

    @Test
    void aFullViewFetchDeserializesAsTheFullVariant() throws Exception {
        FetchInteractionResponseBody body = fetch("full");

        assertThat(body).isInstanceOf(InteractionFetchResponse.class);
        assertCarriesWhatTheMapperReads((InteractionFetchResponse) body);
    }

    @Test
    void theSlimVariantMapsOntoTheFullOneWithEverythingTheMapperReads() throws Exception {
        FetchInteractionResponseBody body = fetch("slim");

        assertThat(body).isInstanceOf(SlimInteractionFetchResponse.class);
        assertCarriesWhatTheMapperReads(GoSdkClient.fromSlim((SlimInteractionFetchResponse) body));
    }

    @Test
    void aFetchWithoutAViewIsReadAsTheFullView() throws Exception {
        FetchInteractionResponseBody body = fetch(null);

        assertThat(body).isInstanceOf(UnknownFetchInteractionResponseBody.class);
        JsonNode json = ((UnknownFetchInteractionResponseBody) body).asJson();
        assertCarriesWhatTheMapperReads(
                GoSdkClient.asFullView(json, InteractionFetchResponse.class, "fetch-interaction", "inst-1").orElseThrow());
    }

    @Test
    void aStateFetchWithoutAViewIsReadAsTheFullView() throws Exception {
        GetJourneyStateResponseBody body = JSON.getMapper().readValue("""
                {"instanceId": "inst-1", "status": "Completed",
                 "context": {"process": {"steps": [{"name": "Facematch Verification", "result": {"status": "complete"}}]}},
                 "result": {"status": "complete", "outcomeClassification": "positive"}}
                """, GetJourneyStateResponseBody.class);

        assertThat(body).isInstanceOf(UnknownGetJourneyStateResponseBody.class);
        StateFetchResponse state = GoSdkClient.asFullView(((UnknownGetJourneyStateResponseBody) body).asJson(),
                StateFetchResponse.class, "journey-state", "inst-1").orElseThrow();
        assertThat(state.result()).isPresent();
        assertThat(state.context()).isPresent();
    }

    /** An in-band error is not read as a journey: GoSdkClient checks inBandError first. */
    @Test
    void anInBandErrorWithoutAViewIsStillAnError() throws Exception {
        FetchInteractionResponseBody body = JSON.getMapper().readValue(
                "{\"status\": \"error\", \"code\": 410, \"message\": \"gone\"}", FetchInteractionResponseBody.class);

        JsonNode json = ((UnknownFetchInteractionResponseBody) body).asJson();
        assertThat(GoSdkClient.inBandError(json)).isNotNull();
    }
}
