package io.kestra.plugin.qlikcloud.apps;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Runs a real reload against a live Qlik Cloud tenant. Skipped unless QLIK_TENANT_URL, QLIK_API_KEY,
 * and QLIK_APP_ID are all set; not wired into CI (no live Qlik Cloud credentials available there).
 */
@KestraTest
@EnabledIfEnvironmentVariable(named = "QLIK_TENANT_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QLIK_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QLIK_APP_ID", matches = ".+")
class ReloadRealTenantTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void reloadsARealApp() throws Exception {
        Reload task = Reload.builder()
            .id("real-tenant-reload")
            .type(Reload.class.getName())
            .tenantUrl(Property.ofValue(System.getenv("QLIK_TENANT_URL")))
            .apiKey(Property.ofValue(System.getenv("QLIK_API_KEY")))
            .appId(Property.ofValue(System.getenv("QLIK_APP_ID")))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        Reload.Output output = task.run(runContext);

        assertThat(output.getStatus(), is("SUCCEEDED"));
        assertThat(output.getReloadId(), is(notNullValue()));
    }
}
