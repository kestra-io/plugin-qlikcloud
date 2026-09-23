package io.kestra.plugin.qlikcloud.automations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 28283)
class RunAutomationTest {
    @Inject
    private RunContextFactory runContextFactory;

    private RunAutomation.RunAutomationBuilder<?, ?> baseBuilder(WireMockRuntimeInfo wireMockRuntimeInfo) {
        return RunAutomation.builder()
            .id("run-automation-task")
            .type(RunAutomation.class.getName())
            .tenantUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiKey(Property.ofValue("test-key"))
            .automationId(Property.ofValue("automation-1"))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)));
    }

    @Test
    void succeedsWhenFinished(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .withRequestBody(matchingJsonPath("$.context", equalTo("api")))
            .willReturn(okJson("{\"id\": \"run-1\", \"status\": \"not started\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-1"))
            .willReturn(okJson("{\"id\": \"run-1\", \"status\": \"finished\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1")).willReturn(okJson("{\"name\": \"My automation\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": [{\"spaceId\": \"space-1\"}]}")));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        RunAutomation.Output output = task.run(runContext);

        assertThat(output.getRunId(), is("run-1"));
        assertThat(output.getAutomationId(), is("automation-1"));
        assertThat(output.getAutomationName(), is("My automation"));
        assertThat(output.getSpaceId(), is("space-1"));
        assertThat(output.getStatus(), is("finished"));
        assertThat(output.getStopTime(), is(notNullValue()));
    }

    @Test
    void warningsSucceedByDefault(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(okJson("{\"id\": \"run-2\", \"status\": \"running\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-2"))
            .willReturn(okJson("{\"id\": \"run-2\", \"status\": \"finished with warnings\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1")).willReturn(okJson("{\"name\": \"My automation\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        RunAutomation.Output output = task.run(runContext);

        assertThat(output.getStatus(), is("finished with warnings"));
    }

    @Test
    void warningsFailWhenFailOnWarningsIsSet(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(okJson("{\"id\": \"run-3\", \"status\": \"running\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-3"))
            .willReturn(okJson("{\"id\": \"run-3\", \"status\": \"finished with warnings\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1")).willReturn(okJson("{\"name\": \"My automation\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).failOnWarnings(Property.ofValue(true)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        assertThrows(IllegalStateException.class, () -> task.run(runContext));
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "must stop", "stopped", "exceeded limit"})
    void failsOnFailureStatuses(String status, WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(okJson("{\"id\": \"run-4\", \"status\": \"running\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-4"))
            .willReturn(okJson("{\"id\": \"run-4\", \"status\": \"" + status + "\", \"message\": \"boom\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1")).willReturn(okJson("{\"name\": \"My automation\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(e.getMessage(), allOf(containsString(status), containsString("boom")));
    }

    @Test
    void unknownStatusKeepsPolling(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(okJson("{\"id\": \"run-5\", \"status\": \"running\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-5"))
            .inScenario("unknown-status")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"id\": \"run-5\", \"status\": \"a future qlik status\"}"))
            .willSetStateTo("done"));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-5"))
            .inScenario("unknown-status")
            .whenScenarioStateIs("done")
            .willReturn(okJson("{\"id\": \"run-5\", \"status\": \"finished\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1")).willReturn(okJson("{\"name\": \"My automation\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        RunAutomation.Output output = task.run(runContext);

        assertThat(output.getStatus(), is("finished"));
        verify(2, getRequestedFor(urlEqualTo("/api/v1/automations/automation-1/runs/run-5")));
    }

    @Test
    void reattachAdoptsAlreadyFinishedRun(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(okJson("{\"id\": \"run-6\", \"status\": \"running\"}")));

        RunAutomation firstAttempt = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, firstAttempt, Map.of());
        firstAttempt.run(runContext);

        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-6"))
            .willReturn(okJson("{\"id\": \"run-6\", \"status\": \"finished\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1")).willReturn(okJson("{\"name\": \"My automation\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        RunAutomation secondAttempt = baseBuilder(wireMockRuntimeInfo).build();
        RunAutomation.Output output = secondAttempt.run(runContext);

        assertThat(output.getRunId(), is("run-6"));
        assertThat(output.getStatus(), is("finished"));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/automations/automation-1/runs")));
    }

    @Test
    void killStopsTheRemoteRun(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(okJson("{\"id\": \"run-7\", \"status\": \"running\"}")));
        stubFor(get(urlEqualTo("/api/v1/automations/automation-1/runs/run-7"))
            .willReturn(okJson("{\"id\": \"run-7\", \"status\": \"running\"}")));
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs/run-7/actions/stop"))
            .willReturn(aResponse().withStatus(204)));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).maxDuration(Property.ofValue(Duration.ofSeconds(30))).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        CompletableFuture<RunAutomation.Output> future = CompletableFuture.supplyAsync(() -> {
            try {
                return task.run(runContext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(300);
        task.kill();

        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertThat(e.getCause().getCause(), instanceOf(InterruptedException.class));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/automations/automation-1/runs/run-7/actions/stop")));
    }

    @Test
    void notFoundOnTriggerNamesTheAutomationId(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/automations/automation-1/runs"))
            .willReturn(aResponse().withStatus(404).withBody("""
                {"errors": [{"code": "NOT-FOUND", "title": "Not Found", "detail": "Resource not found."}]}
                """)));

        RunAutomation task = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(e.getMessage(), allOf(containsString("automation-1"), containsString("not found")));
    }
}
