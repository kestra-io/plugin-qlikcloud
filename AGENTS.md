# Kestra Qlik Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.qlikcloud`.
- `io.kestra.plugin.qlikcloud.apps.Reload`: triggers a Qlik Cloud app reload (Reloads REST API), optionally waits for completion, streams the reload log to internal storage, and emits a Custom app asset on success.
- `io.kestra.plugin.qlikcloud.automations.RunAutomation`: triggers a Qlik Automate automation run and optionally waits for completion.
- Shared internals in the root package: `AbstractQlikCloudTask` (tenant/apiKey connection properties), `AbstractQlikCloudRun` (id-or-name targeting, trigger/poll/reattach/kill loop, reattach state via `runContext.stateStore()`), `QlikCloudClient` (bearer-auth HTTP helper with 429/5xx retry and Qlik error parsing), `QlikResourceResolver` (space/item name lookup), `QlikCloudApiException`.

## Why

- What user problem does this solve? Teams running Qlik Cloud analytics need to trigger app reloads and Qlik Automate automations as part of a broader orchestrated pipeline, instead of relying on Qlik's own scheduler in isolation.
- Why would a team adopt this plugin in a workflow? It lets a Kestra flow trigger a reload or an automation run, wait for it to finish, and react to its outcome (retry, alert, chain a downstream task) alongside the rest of the data pipeline.
- What operational/business outcome does it enable? Reload failures surface in the same place as every other pipeline failure, freshness of Qlik apps becomes trackable as a Kestra asset, and Qlik Automate runs can be sequenced with upstream data-loading tasks.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `qlikcloud` (shared connection, HTTP, resolver, and reattach-loop internals)
- `qlikcloud.apps` (`Reload`)
- `qlikcloud.automations` (`RunAutomation`)

Both tasks target their resource either by id or by `spaceName` + the resource's name (resolved via the Qlik Cloud Spaces and Items APIs), and support `wait`, `pollFrequency`, `maxDuration`, and `reattach` (default `true`, tracked via `runContext.stateStore()` keyed by taskrun id, so a worker restart or a manual restart adopts the in-flight or already-finished run instead of triggering a duplicate).

### Key Plugin Classes

- `io.kestra.plugin.qlikcloud.apps.Reload`
- `io.kestra.plugin.qlikcloud.automations.RunAutomation`
- `io.kestra.plugin.qlikcloud.AbstractQlikCloudTask`
- `io.kestra.plugin.qlikcloud.AbstractQlikCloudRun`
- `io.kestra.plugin.qlikcloud.QlikCloudClient`
- `io.kestra.plugin.qlikcloud.QlikResourceResolver`

### Project Structure

```
plugin-qlikcloud/
├── src/main/java/io/kestra/plugin/qlikcloud/
│   ├── AbstractQlikCloudTask.java
│   ├── AbstractQlikCloudRun.java
│   ├── QlikCloudClient.java
│   ├── QlikCloudApiException.java
│   ├── QlikResourceResolver.java
│   ├── apps/Reload.java
│   └── automations/RunAutomation.java
├── src/test/java/io/kestra/plugin/qlikcloud/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://qlik.dev/apis/rest/reloads/
- https://qlik.dev/apis/rest/automations/
- https://qlik.dev/apis/rest/items/
- https://qlik.dev/apis/rest/spaces/
