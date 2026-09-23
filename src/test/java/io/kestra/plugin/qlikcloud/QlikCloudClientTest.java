package io.kestra.plugin.qlikcloud;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 28284)
class QlikCloudClientTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void normalizeTenantUrlStripsTrailingSlash() {
        assertThat(QlikCloudClient.normalizeTenantUrl("https://mytenant.eu.qlikcloud.com/"), is("https://mytenant.eu.qlikcloud.com"));
    }

    @Test
    void normalizeTenantUrlRejectsAppendedApiPath() {
        assertThrows(IllegalArgumentException.class, () -> QlikCloudClient.normalizeTenantUrl("https://mytenant.eu.qlikcloud.com/api/v1"));
    }

    @Test
    void normalizeTenantUrlRejectsAppendedApiPathWithTrailingSlash() {
        assertThrows(IllegalArgumentException.class, () -> QlikCloudClient.normalizeTenantUrl("https://mytenant.eu.qlikcloud.com/api/v1/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mytenant.eu.qlikcloud.com", "ftp://mytenant.eu.qlikcloud.com", ""})
    void normalizeTenantUrlRejectsMissingOrWrongScheme(String tenantUrl) {
        assertThrows(IllegalArgumentException.class, () -> QlikCloudClient.normalizeTenantUrl(tenantUrl));
    }

    @Test
    void get5xxIsRetried(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/api/v1/apps/app-1"))
            .inScenario("get-5xx")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("retried"));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1"))
            .inScenario("get-5xx")
            .whenScenarioStateIs("retried")
            .willReturn(okJson("{\"attributes\": {\"name\": \"ok\"}}")));

        RunContext runContext = runContextFactory.of(Map.of());
        try (QlikCloudClient client = QlikCloudClient.of(runContext, wireMockRuntimeInfo.getHttpBaseUrl(), "test-key")) {
            var body = client.get("/api/v1/apps/app-1");
            assertThat(body.path("attributes").path("name").asText(), is("ok"));
        }

        verify(2, getRequestedFor(urlEqualTo("/api/v1/apps/app-1")));
    }

    @Test
    void post5xxIsNotRetried(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads")).willReturn(aResponse().withStatus(500)));

        RunContext runContext = runContextFactory.of(Map.of());
        try (QlikCloudClient client = QlikCloudClient.of(runContext, wireMockRuntimeInfo.getHttpBaseUrl(), "test-key")) {
            assertThrows(QlikCloudApiException.class, () -> client.post("/api/v1/reloads", Map.of("appId", "app-1")));
        }

        verify(1, postRequestedFor(urlEqualTo("/api/v1/reloads")));
    }

    @Test
    void get5xxExhaustsRetriesAndFails(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/api/v1/apps/app-1")).willReturn(aResponse().withStatus(503)));

        RunContext runContext = runContextFactory.of(Map.of());
        try (QlikCloudClient client = QlikCloudClient.of(runContext, wireMockRuntimeInfo.getHttpBaseUrl(), "test-key")) {
            assertThrows(QlikCloudApiException.class, () -> client.get("/api/v1/apps/app-1"));
        }
    }
}
