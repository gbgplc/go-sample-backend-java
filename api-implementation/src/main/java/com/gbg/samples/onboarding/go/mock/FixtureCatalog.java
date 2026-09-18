package com.gbg.samples.onboarding.go.mock;

import com.gbg.samples.onboarding.config.AppConfigProperties;
import com.gbg.samples.onboarding.go.mock.fixtures.MeridianHealthFixtures;
import com.gbg.samples.onboarding.go.mock.fixtures.NorthbankFixtures;
import com.gbg.samples.onboarding.go.mock.fixtures.RidgelinePlayFixtures;
import org.springframework.stereotype.Component;

/** Picks the one market's canned scenarios this deployment fronts, from {@code app.market}. */
@Component
public class FixtureCatalog {

    private final MarketFixtures fixtures;

    public FixtureCatalog(AppConfigProperties appConfig) {
        this.fixtures = switch (appConfig.market() == null ? "" : appConfig.market()) {
            case "meridian-health" -> MeridianHealthFixtures.build();
            case "ridgeline-play" -> RidgelinePlayFixtures.build();
            default -> NorthbankFixtures.build();
        };
    }

    public MarketFixtures fixtures() {
        return fixtures;
    }
}
