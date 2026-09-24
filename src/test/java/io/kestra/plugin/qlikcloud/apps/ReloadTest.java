package io.kestra.plugin.qlikcloud.apps;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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
@WireMockTest(httpPort = 28282)
class ReloadTest {
    @Inject
    private RunContextFactory runContextFactory;

    private Reload.ReloadBuilder<?, ?> baseBuilder(WireMockRuntimeInfo wireMockRuntimeInfo) {
        return Reload.builder()
            .id("reload-task")
            .type(Reload.class.getName())
            .tenantUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiKey(Property.ofValue("test-key"))
            .appId(Property.ofValue("app-1"))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)));
    }

    @Test
    void succeedsWaitsAndReturnsMetadataAndLog(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-1\", \"status\": \"QUEUED\"}")));
        stubFor(get(urlEqualTo("/api/v1/reloads/reload-1"))
            .willReturn(okJson("{\"id\": \"reload-1\", \"status\": \"SUCCEEDED\"}")));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1/reloads/logs/reload-1"))
            .willReturn(aResponse().withHeader("Content-Type", "text/plain").withBody("log line 1\nlog line 2")));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1"))
            .willReturn(okJson("{\"attributes\": {\"name\": \"Sales Dashboard\", \"lastReloadTime\": \"2024-01-01T00:00:00Z\"}}")));
        stubFor(get(urlPathEqualTo("/api/v1/items"))
            .withQueryParam("resourceId", equalTo("app-1"))
            .willReturn(okJson("{\"data\": [{\"spaceId\": \"space-1\"}]}")));

        Reload task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        // this also exercises the OSS no-op asset-emission path: runContext.assets().emit() always
        // throws UnsupportedOperationException outside EE, and the task must still succeed.
        Reload.Output output = task.run(runContext);

        assertThat(output.getReloadId(), is("reload-1"));
        assertThat(output.getAppId(), is("app-1"));
        assertThat(output.getAppName(), is("Sales Dashboard"));
        assertThat(output.getSpaceId(), is("space-1"));
        assertThat(output.getStatus(), is("SUCCEEDED"));
        assertThat(output.getLastReloadTime(), is(notNullValue()));
        assertThat(output.getLogUri(), is(notNullValue()));
        assertThat(output.getLogUri().toString(), containsString("kestra://"));
    }

    @Test
    void streamsALargeLogWithoutTruncatingStorageContent(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        int lineCount = 20_000;
        StringBuilder hugeLog = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            hugeLog.append("line ").append(i).append('\n');
        }

        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-huge\", \"status\": \"QUEUED\"}")));
        stubFor(get(urlEqualTo("/api/v1/reloads/reload-huge"))
            .willReturn(okJson("{\"id\": \"reload-huge\", \"status\": \"SUCCEEDED\"}")));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1/reloads/logs/reload-huge"))
            .willReturn(aResponse().withHeader("Content-Type", "text/plain").withBody(hugeLog.toString())));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1")).willReturn(okJson("{\"attributes\": {}}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        Reload task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Reload.Output output = task.run(runContext);

        assertThat(output.getLogUri(), is(notNullValue()));

        // the log stored in internal storage must be complete, not just the bounded tail logged to the task logger
        long storedLineCount;
        try (
            var stream = runContext.storage().getFile(output.getLogUri());
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
        ) {
            storedLineCount = reader.lines().count();
        }
        assertThat(storedLineCount, is((long) lineCount));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELED", "EXCEEDED_LIMIT"})
    void failsOnNonSucceededTerminalStatus(String status, WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-2\", \"status\": \"QUEUED\"}")));
        stubFor(get(urlEqualTo("/api/v1/reloads/reload-2"))
            .willReturn(okJson("{\"id\": \"reload-2\", \"status\": \"" + status + "\", \"errorCode\": \"E1\", \"errorMessage\": \"boom\"}")));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1/reloads/logs/reload-2"))
            .willReturn(aResponse().withStatus(404)));

        Reload task = baseBuilder(wireMockRuntimeInfo).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(e.getMessage(), allOf(containsString(status), containsString("E1"), containsString("boom")));
    }

    @Test
    void timesOutWhenStillRunningPastMaxDuration(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-4\", \"status\": \"QUEUED\"}")));
        stubFor(get(urlEqualTo("/api/v1/reloads/reload-4"))
            .willReturn(okJson("{\"id\": \"reload-4\", \"status\": \"RELOADING\"}")));

        Reload task = baseBuilder(wireMockRuntimeInfo).maxDuration(Property.ofValue(Duration.ofMillis(300))).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        TimeoutException e = assertThrows(TimeoutException.class, () -> task.run(runContext));
        assertThat(e.getMessage(), containsString("RELOADING"));
        // the state entry is left in place on timeout so a retry/restart reattaches instead of duplicating
        verify(0, postRequestedFor(urlEqualTo("/api/v1/reloads/reload-4/actions/cancel")));
    }

    @Test
    void doesNotPollWhenWaitIsFalse(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-5\", \"status\": \"QUEUED\"}")));

        Reload task = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Reload.Output output = task.run(runContext);

        assertThat(output.getReloadId(), is("reload-5"));
        assertThat(output.getStatus(), is("QUEUED"));
        assertThat(output.getEndTime(), is(nullValue()));
        verify(0, getRequestedFor(urlEqualTo("/api/v1/reloads/reload-5")));
    }

    @Test
    void reattachAdoptsRunningReloadInsteadOfRetriggering(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-6\", \"status\": \"QUEUED\"}")));

        Reload firstAttempt = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, firstAttempt, Map.of());
        firstAttempt.run(runContext);

        stubFor(get(urlEqualTo("/api/v1/reloads/reload-6"))
            .inScenario("reattach-running")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"id\": \"reload-6\", \"status\": \"RELOADING\"}"))
            .willSetStateTo("done"));
        stubFor(get(urlEqualTo("/api/v1/reloads/reload-6"))
            .inScenario("reattach-running")
            .whenScenarioStateIs("done")
            .willReturn(okJson("{\"id\": \"reload-6\", \"status\": \"SUCCEEDED\"}")));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1/reloads/logs/reload-6")).willReturn(aResponse().withStatus(404)));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1")).willReturn(okJson("{\"attributes\": {}}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        Reload secondAttempt = baseBuilder(wireMockRuntimeInfo).build();
        Reload.Output output = secondAttempt.run(runContext);

        assertThat(output.getReloadId(), is("reload-6"));
        assertThat(output.getStatus(), is("SUCCEEDED"));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/reloads")));
    }

    @Test
    void reattachAdoptsAlreadyFinishedReload(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-7\", \"status\": \"QUEUED\"}")));

        Reload firstAttempt = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, firstAttempt, Map.of());
        firstAttempt.run(runContext);

        stubFor(get(urlEqualTo("/api/v1/reloads/reload-7"))
            .willReturn(okJson("{\"id\": \"reload-7\", \"status\": \"SUCCEEDED\"}")));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1/reloads/logs/reload-7")).willReturn(aResponse().withStatus(404)));
        stubFor(get(urlEqualTo("/api/v1/apps/app-1")).willReturn(okJson("{\"attributes\": {}}")));
        stubFor(get(urlPathEqualTo("/api/v1/items")).willReturn(okJson("{\"data\": []}")));

        Reload secondAttempt = baseBuilder(wireMockRuntimeInfo).build();
        Reload.Output output = secondAttempt.run(runContext);

        assertThat(output.getReloadId(), is("reload-7"));
        assertThat(output.getStatus(), is("SUCCEEDED"));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/reloads")));
    }

    @Test
    void reattachRetriggersWhenStoredReloadIsGone(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .inScenario("stale-state")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"id\": \"reload-8\", \"status\": \"QUEUED\"}"))
            .willSetStateTo("first triggered"));

        Reload firstAttempt = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, firstAttempt, Map.of());
        firstAttempt.run(runContext);

        stubFor(get(urlEqualTo("/api/v1/reloads/reload-8")).willReturn(aResponse().withStatus(404)));
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .inScenario("stale-state")
            .whenScenarioStateIs("first triggered")
            .willReturn(okJson("{\"id\": \"reload-9\", \"status\": \"QUEUED\"}")));

        Reload secondAttempt = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        Reload.Output output = secondAttempt.run(runContext);

        assertThat(output.getReloadId(), is("reload-9"));
        verify(2, postRequestedFor(urlEqualTo("/api/v1/reloads")));
    }

    @Test
    void killCancelsTheRemoteReload(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(okJson("{\"id\": \"reload-10\", \"status\": \"QUEUED\"}")));
        stubFor(get(urlEqualTo("/api/v1/reloads/reload-10"))
            .willReturn(okJson("{\"id\": \"reload-10\", \"status\": \"RELOADING\"}")));
        stubFor(post(urlEqualTo("/api/v1/reloads/reload-10/actions/cancel"))
            .willReturn(aResponse().withStatus(204)));

        Reload task = baseBuilder(wireMockRuntimeInfo)
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        CompletableFuture<Reload.Output> future = CompletableFuture.supplyAsync(() -> {
            try {
                return task.run(runContext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(300);
        task.kill();

        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertThat(e.getCause().getCause(), instanceOf(InterruptedException.class));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/reloads/reload-10/actions/cancel")));
    }

    @Test
    void failsFastOnPendingReloadInsteadOfRetryingAs429(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        // A short Retry-After keeps this test fast: the underlying Apache HTTP client (which Kestra's
        // HttpClient does not expose a way to reconfigure) retries a 429 once on its own before our own
        // code ever sees it, honoring this header. The point of this test is that our own client must not
        // add any further, additional backoff of its own on top of that single, unavoidable retry.
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody("""
                {"errors": [{"code": "RELOADS-007", "title": "Too Many Requests", "detail": "A pending reload request already exists for this app"}]}
                """)));

        Reload task = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        long start = System.currentTimeMillis();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(e.getMessage(), allOf(containsString("already pending"), containsString("app-1")));
        assertThat("must not add its own extra backoff beyond the transport's single built-in 429 retry", elapsedMs, lessThan(3000L));
    }

    @Test
    void failsFastOn429WithoutRetryingEvenWithoutTheReloadsPendingErrorCode(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        // The spec documents no other meaning for a 429 on this endpoint, so the trigger POST never
        // retries a 429 regardless of the error code — unlike an ordinary rate limit on other endpoints.
        // No request-count assertion here: the underlying Apache HttpClient (see the comment on the
        // sibling test above) retries a 429 once on its own before our own code ever sees the response,
        // regardless of Retry-After; the point of this test is only the friendly, fail-fast message.
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(aResponse().withStatus(429).withBody("""
                {"errors": [{"code": "TOO-MANY-REQUESTS", "title": "Too Many Requests", "detail": "slow down"}]}
                """)));

        Reload task = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(e.getMessage(), allOf(containsString("already pending"), containsString("app-1")));
    }

    @Test
    void failsWithQuotaMessageOn403ReloadFrequencyLimitReached(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(aResponse().withStatus(403).withBody("""
                {"errors": [{"code": "RELOADS-013", "title": "Forbidden", "detail": "Reload frequency limit reached"}]}
                """)));

        Reload task = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(e.getMessage(), allOf(containsString("quota"), containsString("app-1")));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/reloads")));
    }

    @Test
    void notFoundOnTriggerNamesTheAppId(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/reloads"))
            .willReturn(aResponse().withStatus(404).withBody("""
                {"errors": [{"code": "RELOADS-004", "title": "Not Found", "detail": "Resource not found."}]}
                """)));

        Reload task = baseBuilder(wireMockRuntimeInfo).wait(Property.ofValue(false)).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(e.getMessage(), allOf(containsString("app-1"), containsString("not found")));
    }

    @Test
    void assetDisplayNameIsTheAppName() throws Exception {
        Reload task = Reload.builder()
            .id("reload-task")
            .type(Reload.class.getName())
            .tenantUrl(Property.ofValue("http://localhost:1"))
            .apiKey(Property.ofValue("test-key"))
            .appId(Property.ofValue("app-1"))
            .build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var outcome = new io.kestra.plugin.qlikcloud.AbstractQlikCloudRun.RunOutcome(
            "app-1", "space-1", "reload-1",
            new io.kestra.plugin.qlikcloud.AbstractQlikCloudRun.RunStatus("SUCCEEDED", true, true, null),
            java.time.Instant.now(), java.time.Instant.now(), true
        );
        var metadata = new Reload.AppMetadata("Sales Dashboard", "space-1", java.time.Instant.now());

        var asset = task.buildAsset(runContext, outcome, metadata);

        assertThat(asset.getDisplayName(), is("Sales Dashboard"));
        assertThat(asset.getId(), is("app-1"));
    }
}
