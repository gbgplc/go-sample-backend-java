package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.models.InteractionFetchResponse;
import com.gbg.gocore.models.operations.FetchInteractionResponseBody;
import com.gbg.gocore.utils.JSON;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * go-core-sdk 0.2.0's fetch response is a union of a full and a slim
 * variant, chosen by which variant's declared fields best match the JSON.
 * Go's full-view fetch has no {@code context}, which only the full variant
 * declares, so it deserializes as slim. {@link GoSdkClient#fromSlim} maps it
 * back onto the full type the mapper reads.
 */
class FetchResponseVariantTest {

    private static final String FULL_VIEW_FETCH = """
            {
              "instanceId": "inst-1",
              "interactionId": "grn:::gbg:design:interaction:segment1@latest",
              "journey": {"status": "InProgress"},
              "interaction": {"collects": [{"ref": "PrimaryDocument/side1Image", "spec": "required"}]},
              "outstanding": ["PrimaryDocument/side1Image"],
              "instructions": ["LazySide2CollectionRequired"],
              "processing": false
            }
            """;

    @Test
    void aFullViewFetchWithoutContextDeserializesAsTheSlimVariant() throws Exception {
        FetchInteractionResponseBody body = JSON.getMapper().readValue(FULL_VIEW_FETCH, FetchInteractionResponseBody.class);

        assertThat(body.slimInteractionFetchResponse()).isPresent();
        assertThat(body.interactionFetchResponse()).isEmpty();
    }

    @Test
    void theSlimVariantMapsOntoTheFullOneWithEverythingTheMapperReads() throws Exception {
        FetchInteractionResponseBody body = JSON.getMapper().readValue(FULL_VIEW_FETCH, FetchInteractionResponseBody.class);

        InteractionFetchResponse full = GoSdkClient.fromSlim(body.slimInteractionFetchResponse().orElseThrow());

        assertThat(full.instanceId()).isEqualTo("inst-1");
        assertThat(full.interactionId()).contains("grn:::gbg:design:interaction:segment1@latest");
        assertThat(full.journey().status()).isEqualTo("InProgress");
        assertThat(SdkInteractionMapper.collectableRefs(full)).containsExactly("PrimaryDocument/side1Image");
        assertThat(SdkInteractionMapper.stringList(full.outstanding().orElse(null))).containsExactly("PrimaryDocument/side1Image");
        assertThat(SdkInteractionMapper.stringList(full.instructions().orElse(null))).containsExactly("LazySide2CollectionRequired");
    }
}
