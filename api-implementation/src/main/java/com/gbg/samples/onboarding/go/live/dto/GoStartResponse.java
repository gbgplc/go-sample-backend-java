package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * POST journey/start response.
 *
 * @param test     {@code true} when the journey runs on a test scenario: its
 *                 modules return the scenario's outcomes, not live checks.
 *                 Absent on a live run.
 * @param scenario the test scenario the journey runs on; absent on a live run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoStartResponse(String instanceId, String status, String message, Boolean test, String scenario) {

    /**
     * A test run, by Go's own flag when it sent one — {@code "test": false}
     * means not a test run, whatever the instance ID looks like — and by the
     * {@code test_} prefix Go gives a test run's instance ID only when it didn't.
     */
    public boolean isTestRun() {
        return test != null ? test : isTestInstance(instanceId);
    }

    public static boolean isTestInstance(String instanceId) {
        return instanceId != null && (instanceId.startsWith("sandbox_") || instanceId.startsWith("test_"));
    }
}
