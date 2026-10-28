package com.gbg.samples.onboarding.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code go.mode} calls the real GBG Go API ({@code live} or
 * {@code test-scenarios}), by {@link GoProperties#callsGo(String)} — so the
 * wiring and the code ask the same question the same way. Unset means
 * {@code mock}, as it does for {@code GoProperties}.
 */
public class CallsGoCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return GoProperties.callsGo(context.getEnvironment().getProperty("go.mode", "mock"));
    }
}
