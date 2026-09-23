package io.kestra.plugin.qlikcloud;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;

import io.kestra.core.exceptions.ResourceExpiredException;
import io.kestra.core.models.WorkerJobLifecycle;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Shared trigger &rarr; poll &rarr; reattach &rarr; kill loop for a Qlik Cloud task that starts a
 * remote run (an app reload or an automation run) and optionally waits for it to finish. Subclasses
 * plug in how the run is triggered, how its status is read, how it is canceled, and which Items API
 * `resourceType` it targets; everything else (id-or-name targeting, the reattach state, the poll
 * loop, and the kill hook) is common.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQlikCloudRun extends AbstractQlikCloudTask implements WorkerJobLifecycle {
    public static final String STATE_NAME = "qlik-cloud";
    public static final String STATE_SUB_NAME = "run";

    @Schema(
        title = "Space name",
        description = "Name of the Qlik Cloud space containing the target resource, used together with its name property. Ignored when the id is set."
    )
    @PluginProperty(group = "main")
    protected Property<String> spaceName;

    @Schema(
        title = "Wait for completion",
        description = "If true (default), polls Qlik Cloud until the run reaches a final status."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Boolean> wait = Property.ofValue(Boolean.TRUE);

    @Schema(
        title = "Poll frequency",
        description = "Interval between status checks when waiting. Default `PT10S`; cannot usefully go below 1s (Qlik Cloud rate limits)."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(10));

    @Schema(
        title = "Max wait duration",
        description = """
            Ceiling for waiting on completion. Default `PT4H` (Qlik Cloud reloads are capped at about 3h). \
            On timeout the task fails with the last known status; the remote run is NOT canceled."""
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(4));

    @Schema(
        title = "Reattach to an in-flight or already-finished run",
        description = """
            If true (default), the task remembers the run it triggered (keyed by this taskrun id) and, on a \
            worker restart or a manual Restart, adopts it instead of triggering a duplicate: a still-running \
            run resumes polling, an already-finished run is adopted as-is. A stored id no longer found on Qlik \
            Cloud (404) logs a warning and triggers a fresh run. The trigger call itself is never blindly \
            retried on an ambiguous failure (e.g. a timeout), since that could create a duplicate run; the \
            failure surfaces so a flow-level retry or a manual restart can safely re-attach or re-trigger.

            A manual Restart of an execution on the same flow revision keeps the original taskrun ids, so this \
            remembered run is still found afterward. In particular, after a `maxDuration` timeout or a `wait: \
            false` return, the remembered entry is deliberately kept (the remote run may still be in progress): \
            a later Restart of that taskrun then reattaches to it instead of triggering a fresh one. To force a \
            brand-new run on the next attempt regardless of what is remembered — for example after fixing the \
            app or automation itself — set `reattach: false` for that attempt, or clear it by giving the task a \
            different id (a flow update that changes the taskrun id)."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<Boolean> reattach = Property.ofValue(Boolean.TRUE);

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Builder.Default
    private final AtomicReference<Runnable> killable = new AtomicReference<>();

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Builder.Default
    private final AtomicBoolean isKilled = new AtomicBoolean(false);

    protected abstract String resourceType();

    protected abstract Property<String> idProperty();

    protected abstract Property<String> nameProperty();

    protected abstract TriggerOutcome trigger(RunContext runContext, QlikCloudClient client, String resolvedResourceId) throws Exception;

    protected abstract RunStatus fetchStatus(RunContext runContext, QlikCloudClient client, String resolvedResourceId, String runId) throws Exception;

    protected abstract void cancelRemote(RunContext runContext, String resolvedResourceId, String runId);

    /** Log line emitted right after a successful trigger. Override to use resource-specific wording. */
    protected String describeTriggered(String runId, String resolvedResourceId) {
        return "Triggered " + resourceType() + " run '" + runId + "' on " + resourceType() + " '" + resolvedResourceId + "'";
    }

    public record TriggerOutcome(String runId, RunStatus status) {
    }

    public record RunStatus(String raw, boolean terminal, boolean success, String errorMessage) {
    }

    public record RunOutcome(
        String resolvedResourceId,
        String resolvedSpaceId,
        String runId,
        RunStatus status,
        Instant startTime,
        Instant endTime,
        boolean waited
    ) {
    }

    private record StoredRun(String resourceId, String runId) {
    }

    protected final RunOutcome executeRun(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rId = runContext.render(idProperty()).as(String.class).orElse(null);
        String rName = runContext.render(nameProperty()).as(String.class).orElse(null);
        String rSpaceName = runContext.render(this.spaceName).as(String.class).orElse(null);
        validateTargeting(rId, rName, rSpaceName);

        boolean rReattach = runContext.render(this.reattach).as(Boolean.class).orElse(Boolean.TRUE);
        boolean rWait = runContext.render(this.wait).as(Boolean.class).orElse(Boolean.TRUE);
        Duration rPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElse(Duration.ofSeconds(10));
        Duration rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElse(Duration.ofHours(4));

        String taskRunId = runContext.taskRunInfo().taskRunId();
        Instant startTime = Instant.now();

        try (QlikCloudClient client = client(runContext)) {
            StoredRun stored = rReattach ? readState(runContext, taskRunId) : null;

            String resolvedResourceId;
            String resolvedSpaceId = null;
            String runId;
            RunStatus status;

            if (stored != null) {
                try {
                    resolvedResourceId = stored.resourceId();
                    runId = stored.runId();
                    status = fetchStatus(runContext, client, resolvedResourceId, runId);
                    logger.info("Reattached to {} run '{}' (status {}) for taskrun '{}' instead of triggering a new one", resourceType(), runId, status.raw(), taskRunId);
                } catch (QlikCloudApiException e) {
                    if (e.statusCode() != 404) {
                        throw e;
                    }
                    logger.warn("Stored {} run '{}' for taskrun '{}' no longer exists on Qlik Cloud (404); triggering a new one", resourceType(), stored.runId(), taskRunId);
                    stored = null;
                    resolvedResourceId = null;
                    runId = null;
                    status = null;
                }
            } else {
                resolvedResourceId = null;
                runId = null;
                status = null;
            }

            if (stored == null) {
                if (rId != null && !rId.isBlank()) {
                    resolvedResourceId = rId;
                } else {
                    QlikResourceResolver.ResolvedResource resolved = resolveByName(runContext, client, rName, rSpaceName);
                    resolvedResourceId = resolved.resourceId();
                    resolvedSpaceId = resolved.spaceId();
                }

                TriggerOutcome triggered = trigger(runContext, client, resolvedResourceId);
                runId = triggered.runId();
                status = triggered.status();
                logger.info(describeTriggered(runId, resolvedResourceId));

                if (rReattach) {
                    writeState(runContext, taskRunId, resolvedResourceId, runId);
                }
            }

            String finalResourceId = resolvedResourceId;
            String finalRunId = runId;
            killable.set(() -> cancelRemote(runContext, finalResourceId, finalRunId));

            if (!rWait) {
                return new RunOutcome(resolvedResourceId, resolvedSpaceId, runId, status, startTime, null, false);
            }

            Instant deadline = startTime.plus(rMaxDuration);
            while (!status.terminal()) {
                if (isKilled.get()) {
                    throw new InterruptedException("Task was killed while waiting for the " + resourceType() + " run '" + runId + "' to finish");
                }
                if (Instant.now().isAfter(deadline)) {
                    // The state entry is intentionally left in place: the remote run may still be in progress,
                    // and a later retry or restart should reattach to it rather than trigger a duplicate.
                    throw new TimeoutException(
                        "Timed out after " + rMaxDuration + " waiting for the " + resourceType() + " run '" + runId +
                            "' to finish; last known status was '" + status.raw() + "'. The remote run was not canceled."
                    );
                }
                Thread.sleep(rPollFrequency.toMillis());
                status = fetchStatus(runContext, client, resolvedResourceId, runId);
            }

            if (rReattach) {
                deleteState(runContext, taskRunId);
            }

            return new RunOutcome(resolvedResourceId, resolvedSpaceId, runId, status, startTime, Instant.now(), true);
        }
    }

    private void validateTargeting(String rId, String rName, String rSpaceName) {
        boolean hasId = rId != null && !rId.isBlank();
        boolean hasName = rName != null && !rName.isBlank();

        if (hasId == hasName) {
            throw new IllegalArgumentException(
                "Set exactly one of the " + resourceType() + " id, or `spaceName` together with the " + resourceType() + " name — not both, not neither."
            );
        }
        if (hasName && (rSpaceName == null || rSpaceName.isBlank())) {
            throw new IllegalArgumentException("`spaceName` is required when targeting the " + resourceType() + " by name.");
        }
    }

    private QlikResourceResolver.ResolvedResource resolveByName(RunContext runContext, QlikCloudClient client, String rName, String rSpaceName) throws IOException {
        String spaceId = QlikResourceResolver.resolveSpaceId(client, rSpaceName);
        QlikResourceResolver.ResolvedResource resolved = QlikResourceResolver.resolveResourceId(client, spaceId, rSpaceName, rName, resourceType());
        runContext.logger().info("Resolved {} '{}' in space '{}' (id '{}') to id '{}'", resourceType(), rName, rSpaceName, spaceId, resolved.resourceId());
        return resolved;
    }

    private static StoredRun readState(RunContext runContext, String taskRunId) throws IOException {
        try (var stream = runContext.stateStore().getState(STATE_NAME, STATE_SUB_NAME, taskRunId)) {
            JsonNode node = JacksonMapper.ofJson().readTree(stream);
            return new StoredRun(node.path("resourceId").asText(), node.path("runId").asText());
        } catch (FileNotFoundException | ResourceExpiredException e) {
            return null;
        }
    }

    private static void writeState(RunContext runContext, String taskRunId, String resourceId, String runId) throws IOException {
        String json = JacksonMapper.ofJson().writeValueAsString(Map.of("resourceId", resourceId, "runId", runId));
        runContext.stateStore().putState(STATE_NAME, STATE_SUB_NAME, taskRunId, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteState(RunContext runContext, String taskRunId) throws IOException {
        runContext.stateStore().deleteState(STATE_NAME, STATE_SUB_NAME, taskRunId);
    }

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            try {
                Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
            } catch (Exception e) {
                // kill() must never throw; cancelRemote() already logs its own failures, this is a last-resort guard.
            }
        }
    }
}
