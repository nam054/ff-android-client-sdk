package io.harness.cfsdk.mock;

import org.jetbrains.annotations.NotNull;

import io.harness.cfsdk.CfClient;
import io.harness.cfsdk.CfConfiguration;
import io.harness.cfsdk.cloud.analytics.AnalyticsManager;
import io.harness.cfsdk.cloud.core.model.Evaluation;
import io.harness.cfsdk.cloud.factories.CloudFactory;

public class MockedCfClient extends CfClient {

    private MockedAnalyticsManager analyticsManager;

    public MockedCfClient(CloudFactory cloudFactory) {

        super(cloudFactory);
    }

    @NotNull
    @Override
    protected AnalyticsManager getAnalyticsManager(

            CfConfiguration configuration,
            String environmentID,
            String cluster

    ) {

        if (analyticsManager == null) {

            analyticsManager = new MockedAnalyticsManager(

                    environmentID,
                    cloud.getAuthToken(),
                    configuration
            );
        }
        return analyticsManager;
    }

    @Override
    protected boolean canPushToMetrics(Evaluation result) {

        return this.target.isValid() &&
                analyticsEnabled &&
                analyticsManager != null;
    }
}
