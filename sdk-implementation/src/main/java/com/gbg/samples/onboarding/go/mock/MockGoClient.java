package com.gbg.samples.onboarding.go.mock;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;
import com.gbg.samples.onboarding.api.dto.RecordResponse;
import com.gbg.samples.onboarding.api.dto.StagePlanEntry;
import com.gbg.samples.onboarding.api.dto.StageState;
import com.gbg.samples.onboarding.api.dto.StateResponse;
import com.gbg.samples.onboarding.api.dto.SubmitInteractionResponse;
import com.gbg.samples.onboarding.go.GoClient;
import com.gbg.samples.onboarding.go.GoStartResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone canned-fixture Go client (front-end handoff, section 5 —
 * "mock mode"). This is the Java twin of the front end's own
 * {@code MockTransport}: same scenario-script shape, same branching and
 * stage-plan rules, so a session driven through this client behaves
 * identically whichever side of the proxy is running mocked.
 */
@Component
@ConditionalOnProperty(prefix = "go", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockGoClient implements GoClient {

    private final MarketFixtures fixtures;
    /**
     * Bounded and access-ordered, the same as the live clients' per-instance
     * caches: nothing removes an instance when its session ends, so an
     * unbounded map grows with every {@code POST /v1/sessions} for the life
     * of the process. Past the bound the least recently used instance goes,
     * and a session still pointing at it gets the ordinary "session ended".
     */
    static final int MAX_INSTANCES = 10_000;

    private final Map<String, MockInstance> instances = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MockInstance> eldest) {
                    return size() > MAX_INSTANCES;
                }
            });
    private final AtomicLong counter = new AtomicLong();

    public MockGoClient(FixtureCatalog catalog) {
        this.fixtures = catalog.fixtures();
    }

    private static final class MockInstance {
        volatile String scenarioId;
        volatile int stepIndex;
    }

    private MockInstance require(String instanceId) {
        MockInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw OnboardingException.sessionExpired("Your session has ended. Start again to continue.");
        }
        return instance;
    }

    private Scenario scenario(MockInstance instance) {
        return fixtures.getScenarios().get(instance.scenarioId);
    }

    private ScenarioStep currentStep(MockInstance instance) {
        List<ScenarioStep> steps = scenario(instance).getSteps();
        int index = Math.min(instance.stepIndex, steps.size() - 1);
        return steps.get(index);
    }

    private static JourneyStatus statusOf(ScenarioStep step) {
        if (step.getKind() == com.gbg.samples.onboarding.api.dto.ScreenKind.PROCESSING) return JourneyStatus.IN_PROGRESS;
        if (step.getKind() == com.gbg.samples.onboarding.api.dto.ScreenKind.RESULT
                && step.getSummary() != null && !step.getSummary().isEmpty()) {
            return JourneyStatus.COMPLETED;
        }
        return JourneyStatus.PENDING_INPUT;
    }

    /**
     * One row per distinct stage label, in order of first appearance. A stage
     * is `done` once its first occurrence is behind the current step and it
     * isn't the current stage; matching by label (not position) is what lets
     * a repeated stage — banking's "Decision" shows twice around the
     * referral upload — flip back to `active` on the second visit instead of
     * getting stuck.
     */
    private static List<StagePlanEntry> buildStagePlan(List<ScenarioStep> steps, int currentIndex) {
        String currentStage = steps.get(currentIndex).getStage();
        List<String[]> seen = new ArrayList<>(); // [label, firstIndex]
        for (int i = 0; i < steps.size(); i++) {
            String label = steps.get(i).getStage();
            boolean known = seen.stream().anyMatch(e -> e[0].equals(label));
            if (!known) {
                seen.add(new String[]{label, String.valueOf(i)});
            }
        }
        List<StagePlanEntry> plan = new ArrayList<>();
        for (String[] entry : seen) {
            String label = entry[0];
            int first = Integer.parseInt(entry[1]);
            if (label.equals(currentStage)) {
                plan.add(new StagePlanEntry(label, StageState.ACTIVE));
            } else {
                plan.add(new StagePlanEntry(label, first < currentIndex ? StageState.DONE : StageState.UPCOMING));
            }
        }
        return plan;
    }

    private static Interaction toInteraction(ScenarioStep s, String id, List<StagePlanEntry> stagePlan) {
        List<com.gbg.samples.onboarding.api.dto.ChoiceOption> options = s.getOptions() == null ? null
                : s.getOptions().stream()
                    .map(o -> new com.gbg.samples.onboarding.api.dto.ChoiceOption(o.getValue(), o.getLabel(), o.getDetail(), o.getIcon()))
                    .toList();
        return new Interaction(
                id, s.getKind(), s.getStage(), s.getEyebrow(), s.getTitle(), s.getBody(), s.getNote(),
                s.getCta(), s.getSecondaryCta(), s.getCaptureType(), s.getAccepted(), s.getFields(), options,
                s.getChecks(), s.getModules(), s.getModuleRuns(), s.getDecision(), s.getTiming(),
                s.getSummary(), s.getRecordNote(), stagePlan
        );
    }

    private static RecordResponse toRecord(ScenarioStep s) {
        return new RecordResponse(
                s.getDecision() == null ? com.gbg.samples.onboarding.api.dto.Decision.PASS : s.getDecision(),
                s.getTitle(),
                s.getTiming() == null ? "" : s.getTiming(),
                s.getBody() == null ? "" : s.getBody(),
                s.getCta() == null ? "Done" : s.getCta(),
                s.getModuleRuns() == null ? List.of() : s.getModuleRuns(),
                s.getSummary() == null ? List.of() : s.getSummary(),
                s.getRecordNote(),
                // Every mock scenario is a designed outcome — a decision the
                // journey actually reached. There is no "the platform broke"
                // fixture for it to represent.
                false
        );
    }

    @Override
    public GoStartResult startJourney(String resourceId, Map<String, Object> prefill, String scenarioHint) {
        String instanceId = "mock_" + counter.incrementAndGet();
        MockInstance instance = new MockInstance();
        instance.scenarioId = (scenarioHint != null && fixtures.getScenarios().containsKey(scenarioHint))
                ? scenarioHint
                : fixtures.getDefaultScenarioId();
        instance.stepIndex = 0;
        instances.put(instanceId, instance);

        ScenarioStep step = currentStep(instance);
        Interaction interaction = toInteraction(step, instanceId + "_0", buildStagePlan(scenario(instance).getSteps(), 0));
        return new GoStartResult(instanceId, statusOf(step), interaction);
    }

    @Override
    public SubmitInteractionResponse fetchInteraction(String instanceId) {
        MockInstance instance = require(instanceId);
        ScenarioStep step = currentStep(instance);
        List<StagePlanEntry> plan = buildStagePlan(scenario(instance).getSteps(), Math.min(instance.stepIndex, scenario(instance).getSteps().size() - 1));
        Interaction interaction = toInteraction(step, instanceId + "_" + instance.stepIndex, plan);
        return new SubmitInteractionResponse(statusOf(step), interaction);
    }

    @Override
    public SubmitInteractionResponse submitInteraction(String instanceId, String interactionId, Map<String, Object> data) {
        MockInstance instance = require(instanceId);
        ScenarioStep step = currentStep(instance);
        String expectedId = instanceId + "_" + instance.stepIndex;
        if (!expectedId.equals(interactionId)) {
            throw OnboardingException.interactionStale("This step has moved on. Refetching the current one.");
        }

        // Branching choice: switch scenario, continuing at the same position in
        // the target scenario's own step list (both share the prefix up to here).
        if (step.getKind() == com.gbg.samples.onboarding.api.dto.ScreenKind.CHOICE && step.getOptions() != null) {
            Object chosenValue = data == null ? null : data.get("value");
            step.getOptions().stream()
                    .filter(o -> o.getValue().equals(chosenValue))
                    .findFirst()
                    .ifPresent(chosen -> {
                        if (chosen.getBranchTo() != null && !chosen.getBranchTo().equals(instance.scenarioId)) {
                            instance.scenarioId = chosen.getBranchTo();
                        }
                    });
        }

        List<ScenarioStep> targetSteps = scenario(instance).getSteps();
        instance.stepIndex = Math.min(instance.stepIndex + 1, targetSteps.size() - 1);

        ScenarioStep nextStep = currentStep(instance);
        String nextId = instanceId + "_" + instance.stepIndex;
        List<StagePlanEntry> plan = buildStagePlan(targetSteps, instance.stepIndex);
        return new SubmitInteractionResponse(statusOf(nextStep), toInteraction(nextStep, nextId, plan));
    }

    @Override
    public StateResponse fetchState(String instanceId) {
        MockInstance instance = require(instanceId);
        ScenarioStep step = currentStep(instance);
        return new StateResponse(statusOf(step), step.getDecision(), step.getModuleRuns());
    }

    @Override
    public RecordResponse fetchRecord(String instanceId) {
        MockInstance instance = require(instanceId);
        return toRecord(currentStep(instance));
    }
}
