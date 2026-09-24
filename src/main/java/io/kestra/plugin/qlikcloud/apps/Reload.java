package io.kestra.plugin.qlikcloud.apps;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.qlikcloud.AbstractQlikCloudRun;
import io.kestra.plugin.qlikcloud.QlikCloudApiException;
import io.kestra.plugin.qlikcloud.QlikCloudClient;
import io.kestra.plugin.qlikcloud.QlikResourceResolver;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a Qlik Cloud app reload",
    description = """
        Starts a reload of a Qlik Cloud app through the Reloads REST API and, by default, waits for it to \
        reach a final status, streaming the reload log to internal storage. On success it emits a Custom \
        asset (`io.kestra.plugin.qlikcloud.assets.App`) so downstream tools can track the app's freshness; \
        asset emission is a no-op on Kestra OSS. It also requires `assets.enableAuto: true` on this task \
        (a Kestra core setting, defaulting to false) — without it, the asset is silently dropped even \
        though `emitAssets` defaults to true."""
)
@Plugin(
    examples = {
        @Example(
            title = "Reload a Qlik Cloud app by id",
            full = true,
            code = """
                id: qlik_reload_by_id
                namespace: company.team

                tasks:
                  - id: reload
                    type: io.kestra.plugin.qlikcloud.apps.Reload
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    appId: 60f2e3b1a1b2c3d4e5f6a7b8
                    assets:
                      enableAuto: true
                """
        ),
        @Example(
            title = "Reload a Qlik Cloud app by space and app name",
            full = true,
            code = """
                id: qlik_reload_by_name
                namespace: company.team

                tasks:
                  - id: reload
                    type: io.kestra.plugin.qlikcloud.apps.Reload
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    spaceName: Sales Analytics
                    appName: Sales Dashboard
                    assets:
                      enableAuto: true
                """
        ),
        @Example(
            title = "Partial reload with reload variables",
            full = true,
            code = """
                id: qlik_partial_reload
                namespace: company.team

                tasks:
                  - id: reload
                    type: io.kestra.plugin.qlikcloud.apps.Reload
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    appId: 60f2e3b1a1b2c3d4e5f6a7b8
                    partial: true
                    variables:
                      START_DATE: "2024-01-01"
                      END_DATE: "2024-01-31"
                    assets:
                      enableAuto: true
                """
        ),
        @Example(
            title = "Fire-and-forget reload, without waiting for completion",
            full = true,
            code = """
                id: qlik_reload_fire_and_forget
                namespace: company.team

                tasks:
                  - id: reload
                    type: io.kestra.plugin.qlikcloud.apps.Reload
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    appId: 60f2e3b1a1b2c3d4e5f6a7b8
                    wait: false
                """
        )
    }
)
public class Reload extends AbstractQlikCloudRun implements RunnableTask<Reload.Output> {
    private static final String APP_ASSET_TYPE = "io.kestra.plugin.qlikcloud.assets.App";
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELED", "EXCEEDED_LIMIT");
    private static final int LOG_TAIL_LINES = 50;
    private static final int MAX_VARIABLES = 20;
    private static final int MAX_VARIABLE_LENGTH = 256;

    @Schema(title = "App ID", description = "Qlik Cloud app identifier. Mutually exclusive with `spaceName` + `appName`.")
    @PluginProperty(group = "main")
    Property<String> appId;

    @Schema(title = "App name", description = "App name, resolved together with `spaceName`. Mutually exclusive with `appId`.")
    @PluginProperty(group = "main")
    Property<String> appName;

    @Schema(title = "Partial reload", description = "If true, performs a partial reload instead of a full one. Default `false`.")
    @Builder.Default
    @PluginProperty(group = "main")
    Property<Boolean> partial = Property.ofValue(Boolean.FALSE);

    // Not annotated with @Min(1)/@Max(10): jakarta.validation's built-in constraint validators only
    // support Number and CharSequence, not a Property<Integer> wrapper, so Kestra's ModelValidator would
    // throw UnexpectedTypeException (HV000030) the moment any flow using this task is validated,
    // regardless of the actual value. The bound is enforced manually in trigger(), once rendered.
    @Schema(title = "Reload queue weight", description = "Queue priority for the reload, from 1 (lowest) to 10 (highest). Left unset, Qlik Cloud applies its own default.")
    @PluginProperty(group = "main")
    Property<Integer> weight;

    @Schema(
        title = "Reload variables",
        description = "Variables passed to the app's load script. Qlik Cloud accepts at most 20 entries, each key and value up to 256 characters."
    )
    @PluginProperty(group = "main")
    Property<Map<String, String>> variables;

    @Schema(
        title = "Emit an app asset",
        description = """
            If true (default), emits a Custom asset for the app after a successful reload; set to false to opt \
            this task out even when asset emission is otherwise enabled. This is a per-task opt-out on top of \
            Kestra's own `assets.enableAuto` gate (default false): both must be true for the asset to actually \
            be recorded — `emitAssets: true` alone does nothing without `assets: { enableAuto: true }` on this \
            task. No-op on Kestra OSS regardless."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Boolean> emitAssets = Property.ofValue(Boolean.TRUE);

    @Override
    protected String resourceType() {
        return "app";
    }

    @Override
    protected Property<String> idProperty() {
        return this.appId;
    }

    @Override
    protected Property<String> nameProperty() {
        return this.appName;
    }

    @Override
    protected TriggerOutcome trigger(RunContext runContext, QlikCloudClient client, String resolvedAppId) throws Exception {
        boolean rPartial = runContext.render(this.partial).as(Boolean.class).orElse(Boolean.FALSE);
        Integer rWeight = runContext.render(this.weight).as(Integer.class).orElse(null);
        Map<String, String> rVariables = runContext.render(this.variables).asMap(String.class, String.class);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", resolvedAppId);
        body.put("partial", rPartial);

        if (rWeight != null) {
            if (rWeight < 1 || rWeight > 10) {
                throw new IllegalArgumentException("`weight` must be between 1 and 10, got " + rWeight);
            }
            body.put("weight", rWeight);
        }

        if (rVariables != null && !rVariables.isEmpty()) {
            validateVariables(rVariables);
            body.put("variables", rVariables);
        }

        JsonNode response;
        try {
            // A 429 on this endpoint always means a reload is already pending for this app (Qlik Reloads
            // API spec), never a generic rate limit, so it is never retried — see QlikCloudClient.post.
            response = client.post("/api/v1/reloads", body, false);
        } catch (QlikCloudApiException e) {
            if (e.statusCode() == 429) {
                throw new IllegalStateException(
                    "A reload is already pending/in progress for app '" + resolvedAppId + "'; wait for it to finish or cancel it.", e
                );
            }
            if (e.statusCode() == 403 && "RELOADS-013".equals(e.errorCode())) {
                throw new IllegalStateException(
                    "The reload frequency quota for app '" + resolvedAppId + "' has been reached for this tenant; try again later or reduce the reload frequency.", e
                );
            }
            if (e.statusCode() == 404) {
                throw new IllegalStateException("App '" + resolvedAppId + "' not found or not accessible with this API key", e);
            }
            throw e;
        }

        String reloadId = response.path("id").asText(null);
        if (reloadId == null) {
            throw new IllegalStateException("Qlik Cloud did not return a reload id when triggering a reload for app '" + resolvedAppId + "'");
        }

        return new TriggerOutcome(reloadId, statusFromNode(response));
    }

    @Override
    protected String describeTriggered(String runId, String resolvedAppId) {
        return "Triggered reload '" + runId + "' for app '" + resolvedAppId + "'";
    }

    @Override
    protected RunStatus fetchStatus(RunContext runContext, QlikCloudClient client, String resolvedAppId, String reloadId) throws Exception {
        return statusFromNode(client.get("/api/v1/reloads/" + QlikCloudClient.encode(reloadId)));
    }

    @Override
    protected void cancelRemote(RunContext runContext, String resolvedAppId, String reloadId) {
        try (QlikCloudClient client = client(runContext)) {
            client.post("/api/v1/reloads/" + QlikCloudClient.encode(reloadId) + "/actions/cancel", null);
            runContext.logger().info("Canceled Qlik Cloud reload '{}'", reloadId);
        } catch (Exception e) {
            runContext.logger().warn("Failed to cancel Qlik Cloud reload '{}': {}", reloadId, e.getMessage());
        }
    }

    private static void validateVariables(Map<String, String> variables) {
        if (variables.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("`variables` accepts at most " + MAX_VARIABLES + " entries, got " + variables.size());
        }
        variables.forEach((key, value) -> {
            if (key.length() > MAX_VARIABLE_LENGTH || (value != null && value.length() > MAX_VARIABLE_LENGTH)) {
                throw new IllegalArgumentException("`variables` entries are limited to " + MAX_VARIABLE_LENGTH + " characters; entry '" + key + "' exceeds it");
            }
        });
    }

    private static RunStatus statusFromNode(JsonNode node) {
        String status = node.path("status").asText("");
        boolean terminal = TERMINAL_STATUSES.contains(status);
        boolean success = "SUCCEEDED".equals(status);
        String errorMessage = null;

        if (terminal && !success) {
            String code = node.path("errorCode").asText(null);
            String message = node.path("errorMessage").asText(null);
            errorMessage = Stream.of(code, message).filter(Objects::nonNull).reduce((a, b) -> a + " - " + b).orElse(null);
        }

        return new RunStatus(status, terminal, success, errorMessage);
    }

    @Override
    public Reload.Output run(RunContext runContext) throws Exception {
        RunOutcome outcome = executeRun(runContext);

        if (!outcome.waited()) {
            return Output.builder()
                .reloadId(outcome.runId())
                .appId(outcome.resolvedResourceId())
                .status(outcome.status().raw())
                .startTime(outcome.startTime())
                .build();
        }

        RunStatus status = outcome.status();
        URI logUri = streamLog(runContext, outcome.resolvedResourceId(), outcome.runId());

        if (!status.success()) {
            throw new IllegalStateException(
                "Qlik Cloud reload '" + outcome.runId() + "' for app '" + outcome.resolvedResourceId() + "' ended with status '" + status.raw() + "'" +
                    (status.errorMessage() != null ? ": " + status.errorMessage() : "") +
                    (logUri != null ? ". See the reload log at " + logUri : "")
            );
        }

        AppMetadata metadata = fetchAppMetadata(runContext, outcome.resolvedResourceId(), outcome.resolvedSpaceId());

        if (runContext.render(this.emitAssets).as(Boolean.class).orElse(Boolean.TRUE)) {
            emitAsset(runContext, outcome, metadata);
        }

        return Output.builder()
            .reloadId(outcome.runId())
            .appId(outcome.resolvedResourceId())
            .appName(metadata != null ? metadata.name() : null)
            .spaceId(metadata != null ? metadata.spaceId() : null)
            .status(status.raw())
            .startTime(outcome.startTime())
            .endTime(outcome.endTime())
            .duration(Duration.between(outcome.startTime(), outcome.endTime()))
            .lastReloadTime(metadata != null ? metadata.lastReloadTime() : null)
            .logUri(logUri)
            .build();
    }

    private URI streamLog(RunContext runContext, String appId, String reloadId) {
        try {
            return streamLogFromEndpoint(runContext, appId, reloadId);
        } catch (Exception e) {
            runContext.logger().warn("Failed to fetch the reload log stream for reload '{}', falling back to the reload resource's `log` field: {}", reloadId, e.getMessage());
            return streamLogFallback(runContext, reloadId);
        }
    }

    private URI streamLogFromEndpoint(RunContext runContext, String appId, String reloadId) throws Exception {
        try (QlikCloudClient client = client(runContext)) {
            AtomicReference<URI> result = new AtomicReference<>();
            AtomicReference<IOException> writeFailure = new AtomicReference<>();

            client.getStream(
                "/api/v1/apps/" + QlikCloudClient.encode(appId) + "/reloads/logs/" + QlikCloudClient.encode(reloadId),
                response -> {
                    try {
                        result.set(copyLogToStorage(runContext, reloadId, response.getBody()));
                    } catch (IOException e) {
                        writeFailure.set(e);
                    }
                }
            );

            if (writeFailure.get() != null) {
                throw writeFailure.get();
            }
            return result.get();
        }
    }

    private URI streamLogFallback(RunContext runContext, String reloadId) {
        try (QlikCloudClient client = client(runContext)) {
            String logText = client.get("/api/v1/reloads/" + QlikCloudClient.encode(reloadId)).path("log").asText(null);
            if (logText == null || logText.isBlank()) {
                return null;
            }
            // The reload resource embeds the log inline in its JSON body, so the full string is already
            // in memory by the time we get here; still routed through the same bounded-tail copy so it
            // is not duplicated again as a separate List<String> and byte[] on its way to storage.
            return copyLogToStorage(runContext, reloadId, new ByteArrayInputStream(logText.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            runContext.logger().warn("Failed to fetch the reload log fallback for reload '{}': {}", reloadId, e.getMessage());
            return null;
        }
    }

    /**
     * Copies a reload log straight to an internal-storage temp file without buffering the whole log in
     * memory, while keeping only a ring buffer of the last {@link #LOG_TAIL_LINES} lines to log to the
     * task's own logger.
     */
    private URI copyLogToStorage(RunContext runContext, String reloadId, InputStream body) throws IOException {
        Path tempFile = runContext.workingDir().createTempFile(".log");
        Deque<String> tail = new ArrayDeque<>(LOG_TAIL_LINES);
        boolean any = false;

        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                any = true;
                writer.write(line);
                writer.newLine();
                if (tail.size() == LOG_TAIL_LINES) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        }

        if (!any) {
            Files.deleteIfExists(tempFile);
            return null;
        }

        tail.forEach(runContext.logger()::info);
        return runContext.storage().putFile(tempFile.toFile());
    }

    record AppMetadata(String name, String spaceId, Instant lastReloadTime) {
    }

    private AppMetadata fetchAppMetadata(RunContext runContext, String appId, String knownSpaceId) {
        try (QlikCloudClient client = client(runContext)) {
            JsonNode attributes = client.get("/api/v1/apps/" + QlikCloudClient.encode(appId)).path("attributes");
            String name = attributes.path("name").asText(null);
            Instant lastReloadTime = parseInstant(attributes.path("lastReloadTime").asText(null));
            String spaceId = knownSpaceId != null ? knownSpaceId : QlikResourceResolver.resolveSpaceIdForResource(client, appId, resourceType());
            return new AppMetadata(name, spaceId, lastReloadTime);
        } catch (Exception e) {
            runContext.logger().warn("Failed to fetch app metadata for '{}' after a successful reload: {}", appId, e.getMessage());
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** Package-visible so the app-name-as-displayName mapping is unit-testable without an EE asset emitter. */
    Custom buildAsset(RunContext runContext, RunOutcome outcome, AppMetadata metadata) throws Exception {
        Map<String, Object> metadataMap = new LinkedHashMap<>();
        if (metadata != null) {
            if (metadata.name() != null) {
                metadataMap.put("appName", metadata.name());
            }
            if (metadata.spaceId() != null) {
                metadataMap.put("spaceId", metadata.spaceId());
            }
            if (metadata.lastReloadTime() != null) {
                metadataMap.put("lastReloadTime", metadata.lastReloadTime().toString());
            }
        }
        metadataMap.put("reloadId", outcome.runId());
        metadataMap.put("reloadStatus", outcome.status().raw());
        metadataMap.put("tenantUrl", runContext.render(this.tenantUrl).as(String.class).orElse(null));
        metadataMap.put("partial", runContext.render(this.partial).as(Boolean.class).orElse(Boolean.FALSE));

        return Custom.builder()
            .id(outcome.resolvedResourceId())
            .type(APP_ASSET_TYPE)
            .displayName(metadata != null ? metadata.name() : null)
            .metadata(metadataMap)
            .build();
    }

    private void emitAsset(RunContext runContext, RunOutcome outcome, AppMetadata metadata) {
        try {
            Custom asset = buildAsset(runContext, outcome, metadata);
            runContext.assets().emit(new AssetEmit(List.of(), List.of(asset)));
        } catch (UnsupportedOperationException e) {
            runContext.logger().debug("Asset emission is not supported in this edition, skipping.");
        } catch (QueueException e) {
            runContext.logger().warn("Unable to emit the Qlik Cloud app asset '{}'", outcome.resolvedResourceId(), e);
        } catch (Exception e) {
            runContext.logger().warn("Unexpected failure emitting the Qlik Cloud app asset '{}', the reload itself still succeeded", outcome.resolvedResourceId(), e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Reload ID", description = "Qlik Cloud identifier of the triggered reload.")
        private final String reloadId;

        @Schema(title = "App ID", description = "Resolved app identifier — useful when the app was targeted by name.")
        private final String appId;

        @Schema(title = "App name")
        private final String appName;

        @Schema(title = "Space ID", description = "Id of the Qlik Cloud space containing the app.")
        private final String spaceId;

        @Schema(title = "Reload status", description = "One of `QUEUED`, `RELOADING`, `CANCELING`, `SUCCEEDED`, `FAILED`, `CANCELED`, `EXCEEDED_LIMIT`.")
        private final String status;

        @Schema(title = "Start time")
        private final Instant startTime;

        @Schema(title = "End time", description = "Only set when `wait` is true and the reload reached a final status.")
        private final Instant endTime;

        @Schema(title = "Duration", description = "Only set when `wait` is true and the reload reached a final status.")
        private final Duration duration;

        @Schema(title = "Last reload time", description = "Freshness timestamp reported by the app after a successful reload.")
        private final Instant lastReloadTime;

        @Schema(title = "Log URI", description = "Internal storage URI of the full reload log, when it could be fetched.")
        private final URI logUri;
    }
}
