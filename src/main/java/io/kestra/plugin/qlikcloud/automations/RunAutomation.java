package io.kestra.plugin.qlikcloud.automations;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
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
    title = "Run a Qlik Automate automation",
    description = """
        Starts a run of a Qlik Automate automation and, by default, waits for it to reach a final status. \
        `finished with warnings` counts as a success unless `failOnWarnings` is set. Automation inputs are \
        out of scope: they require Triggered mode and a per-automation webhook token."""
)
@Plugin(
    examples = {
        @Example(
            title = "Run a Qlik Automate automation by id",
            full = true,
            code = """
                id: qlik_run_automation_by_id
                namespace: company.team

                tasks:
                  - id: run_automation
                    type: io.kestra.plugin.qlikcloud.automations.RunAutomation
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    automationId: 60f2e3b1a1b2c3d4e5f6a7b8
                """
        ),
        @Example(
            title = "Run a Qlik Automate automation by space and automation name",
            full = true,
            code = """
                id: qlik_run_automation_by_name
                namespace: company.team

                tasks:
                  - id: run_automation
                    type: io.kestra.plugin.qlikcloud.automations.RunAutomation
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    spaceName: Sales Analytics
                    automationName: Refresh source extracts
                """
        ),
        @Example(
            title = "Run an automation, then reload the app it feeds",
            full = true,
            code = """
                id: qlik_automation_then_reload
                namespace: company.team

                tasks:
                  - id: run_automation
                    type: io.kestra.plugin.qlikcloud.automations.RunAutomation
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    automationId: 60f2e3b1a1b2c3d4e5f6a7b8

                  - id: reload_app
                    type: io.kestra.plugin.qlikcloud.apps.Reload
                    tenantUrl: https://mytenant.eu.qlikcloud.com
                    apiKey: "{{ secret('QLIK_API_KEY') }}"
                    appId: 70a3f4c2b2c3d4e5f6a7b8c9
                    assets:
                      enableAuto: true
                """
        )
    }
)
public class RunAutomation extends AbstractQlikCloudRun implements RunnableTask<RunAutomation.Output> {
    // The API reference lists `api_sync` / `api_async` for `context`, but "api" is the value already
    // proven in production to start a run through this endpoint; kept as one constant in case Qlik
    // Cloud stops accepting it.
    private static final String RUN_CONTEXT_VALUE = "api";

    // "must stop" is intentionally excluded from both sets: the spec doesn't document it as final, and
    // in practice it means a stop was requested but the run hasn't finished ending yet (Reload has an
    // equivalent transitional status, "CANCELING") — it keeps polling like any other in-progress status.
    private static final Set<String> TERMINAL_STATUSES = Set.of("finished", "finished with warnings", "failed", "stopped", "exceeded limit");
    private static final Set<String> FAILURE_STATUSES = Set.of("failed", "stopped", "exceeded limit");
    private static final String WARNING_STATUS = "finished with warnings";
    private static final List<String> ERROR_TEXT_FIELDS = List.of("message", "detail", "title", "description");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    @Schema(title = "Automation ID", description = "Qlik Cloud automation identifier. Mutually exclusive with `spaceName` + `automationName`.")
    @PluginProperty(group = "main")
    Property<String> automationId;

    @Schema(title = "Automation name", description = "Automation name, resolved together with `spaceName`. Mutually exclusive with `automationId`.")
    @PluginProperty(group = "main")
    Property<String> automationName;

    @Schema(
        title = "Fail on warnings",
        description = "If true, a run that finished with warnings (`finished with warnings`) fails the task instead of succeeding. Default `false`."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    Property<Boolean> failOnWarnings = Property.ofValue(Boolean.FALSE);

    @Override
    protected String resourceType() {
        return "automation";
    }

    @Override
    protected Property<String> idProperty() {
        return this.automationId;
    }

    @Override
    protected Property<String> nameProperty() {
        return this.automationName;
    }

    @Override
    protected TriggerOutcome trigger(RunContext runContext, QlikCloudClient client, String resolvedAutomationId) throws Exception {
        JsonNode response;
        try {
            response = client.post(
                "/api/v1/automations/" + QlikCloudClient.encode(resolvedAutomationId) + "/runs",
                Map.of("context", RUN_CONTEXT_VALUE)
            );
        } catch (QlikCloudApiException e) {
            if (e.statusCode() == 404) {
                throw new IllegalStateException("Automation '" + resolvedAutomationId + "' not found or not accessible with this API key", e);
            }
            throw e;
        }

        String runId = response.path("id").asText(null);
        if (runId == null) {
            throw new IllegalStateException("Qlik Cloud did not return a run id when starting automation '" + resolvedAutomationId + "'");
        }

        return new TriggerOutcome(runId, statusFromNode(runContext, response));
    }

    @Override
    protected RunStatus fetchStatus(RunContext runContext, QlikCloudClient client, String resolvedAutomationId, String runId) throws Exception {
        JsonNode node = client.get("/api/v1/automations/" + QlikCloudClient.encode(resolvedAutomationId) + "/runs/" + QlikCloudClient.encode(runId));
        return statusFromNode(runContext, node);
    }

    @Override
    protected void cancelRemote(RunContext runContext, String resolvedAutomationId, String runId) {
        try (QlikCloudClient client = client(runContext)) {
            client.post("/api/v1/automations/" + QlikCloudClient.encode(resolvedAutomationId) + "/runs/" + QlikCloudClient.encode(runId) + "/actions/stop", null);
            runContext.logger().info("Stopped Qlik Cloud automation run '{}'", runId);
        } catch (Exception e) {
            runContext.logger().warn("Failed to stop Qlik Cloud automation run '{}': {}", runId, e.getMessage());
        }
    }

    private RunStatus statusFromNode(RunContext runContext, JsonNode node) throws Exception {
        String status = node.path("status").asText("");
        String normalized = status.toLowerCase(Locale.ROOT);
        boolean terminal = TERMINAL_STATUSES.contains(normalized);

        boolean rFailOnWarnings = runContext.render(this.failOnWarnings).as(Boolean.class).orElse(Boolean.FALSE);
        boolean warned = WARNING_STATUS.equals(normalized);
        if (warned) {
            runContext.logger().warn("Qlik Cloud automation run finished with warnings");
        }

        boolean success = terminal && !FAILURE_STATUSES.contains(normalized) && !(warned && rFailOnWarnings);
        String errorMessage = null;
        if (terminal && !success) {
            errorMessage = errorMessageFromNode(node);
        }

        return new RunStatus(status, terminal, success, errorMessage);
    }

    /** Builds a readable message from the run's `error[]` array — the run object has no top-level `message` field. */
    private static String errorMessageFromNode(JsonNode node) {
        JsonNode errors = node.path("error");
        if (!errors.isArray() || errors.isEmpty()) {
            return null;
        }

        String joined = StreamSupport.stream(errors.spliterator(), false)
            .map(RunAutomation::errorEntryText)
            .collect(Collectors.joining("; "));

        return joined.length() > MAX_ERROR_MESSAGE_LENGTH ? joined.substring(0, MAX_ERROR_MESSAGE_LENGTH) : joined;
    }

    private static String errorEntryText(JsonNode entry) {
        return ERROR_TEXT_FIELDS.stream()
            .map(field -> entry.path(field).asText(null))
            .filter(text -> text != null && !text.isBlank())
            .findFirst()
            .orElseGet(entry::toString);
    }

    @Override
    public RunAutomation.Output run(RunContext runContext) throws Exception {
        RunOutcome outcome = executeRun(runContext);

        String rAutomationName = runContext.render(this.automationName).as(String.class).orElse(null);
        String automationName = rAutomationName;
        String spaceId = outcome.resolvedSpaceId();

        if (automationName == null || spaceId == null) {
            Metadata metadata = fetchMetadata(runContext, outcome.resolvedResourceId(), spaceId);
            if (metadata != null) {
                automationName = automationName != null ? automationName : metadata.name();
                spaceId = spaceId != null ? spaceId : metadata.spaceId();
            }
        }

        if (!outcome.waited()) {
            return Output.builder()
                .runId(outcome.runId())
                .automationId(outcome.resolvedResourceId())
                .automationName(automationName)
                .spaceId(spaceId)
                .status(outcome.status().raw())
                .startTime(outcome.startTime())
                .build();
        }

        RunStatus status = outcome.status();
        if (!status.success()) {
            throw new IllegalStateException(
                "Qlik Cloud automation run '" + outcome.runId() + "' for automation '" + outcome.resolvedResourceId() + "' ended with status '" + status.raw() + "'" +
                    (status.errorMessage() != null ? ": " + status.errorMessage() : "")
            );
        }

        return Output.builder()
            .runId(outcome.runId())
            .automationId(outcome.resolvedResourceId())
            .automationName(automationName)
            .spaceId(spaceId)
            .status(status.raw())
            .startTime(outcome.startTime())
            .stopTime(outcome.endTime())
            .build();
    }

    private record Metadata(String name, String spaceId) {
    }

    private Metadata fetchMetadata(RunContext runContext, String automationId, String knownSpaceId) {
        try (QlikCloudClient client = client(runContext)) {
            String name = client.get("/api/v1/automations/" + QlikCloudClient.encode(automationId)).path("name").asText(null);
            String spaceId = knownSpaceId != null ? knownSpaceId : QlikResourceResolver.resolveSpaceIdForResource(client, automationId, resourceType());
            return new Metadata(name, spaceId);
        } catch (Exception e) {
            runContext.logger().warn("Failed to fetch automation metadata for '{}': {}", automationId, e.getMessage());
            return null;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Run ID", description = "Qlik Cloud identifier of the triggered automation run.")
        private final String runId;

        @Schema(title = "Automation ID", description = "Resolved automation identifier — useful when the automation was targeted by name.")
        private final String automationId;

        @Schema(title = "Automation name")
        private final String automationName;

        @Schema(title = "Space ID", description = "Id of the Qlik Cloud space containing the automation.")
        private final String spaceId;

        @Schema(
            title = "Run status",
            description = """
                One of the documented Qlik Automate statuses. Final: `finished`, `finished with warnings`, \
                `failed`, `stopped`, `exceeded limit`. Still running: `not started`, `starting`, `queued`, \
                `running`, `must stop` (a stop was requested but the run hasn't finished ending yet)."""
        )
        private final String status;

        @Schema(title = "Start time")
        private final Instant startTime;

        @Schema(title = "Stop time", description = "Only set when `wait` is true and the run reached a final status.")
        private final Instant stopTime;
    }
}
