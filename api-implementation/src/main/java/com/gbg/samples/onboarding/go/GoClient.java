package com.gbg.samples.onboarding.go;

import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;

import java.util.Map;

/**
 * Everything the session layer needs from GBG Go, named after the v2
 * endpoints it wraps (POST /journey/start, /journey/interaction/fetch,
 * /journey/interaction/submit, /journey/state/fetch). Two implementations:
 * {@link com.gbg.samples.onboarding.go.mock.MockGoClient} for local/demo
 * running with no live credentials, and
 * {@link com.gbg.samples.onboarding.go.live.GoApiClient} for the real thing.
 * The session layer never knows which one is wired in.
 */
public interface GoClient {

    /**
     * @param scenarioHint mock-mode-only affordance (mirrors the front end's own
     *                     {@code ?mock_scenario=} query param) so every designed
     *                     outcome can be reached on demand for review and QA. A
     *                     live client ignores it — which branch plays is Go's
     *                     decision, not the caller's.
     */
    GoStartResult startJourney(String resourceId, Map<String, Object> prefill, String scenarioHint);

    SubmitInteractionResponse fetchInteraction(String instanceId);

    SubmitInteractionResponse submitInteraction(String instanceId, String interactionId, Map<String, Object> data);

    StateResponse fetchState(String instanceId);

    RecordResponse fetchRecord(String instanceId);
}
