# How to use the Qlik Cloud plugin

Trigger and monitor [Qlik Cloud](https://qlik.dev/) app reloads and Qlik Automate automation runs from a Kestra flow.

## Authentication

Every task requires `tenantUrl` (the tenant root, e.g. `https://mytenant.eu.qlikcloud.com` — do not append `/api/v1`) and `apiKey`, sent as a bearer token. `apiKey` accepts either a Qlik Cloud [API key](https://qlik.dev/authenticate/api-key/generate-your-first-api-key/) or a pre-obtained OAuth 2.0 access token; this plugin does not implement the OAuth flow itself. Generate an API key from the tenant's Management Console (Identity & access > API keys), store it as a [secret](https://kestra.io/docs/concepts/secret), and reference it with `{{ secret('QLIK_API_KEY') }}`. You can also set `tenantUrl` and `apiKey` once as [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) for every Qlik Cloud task in a namespace.

## Tasks

Both tasks below target their resource *either* by id *or* by `spaceName` together with the resource's name — exactly one of the two must be set. Name lookup paginates through the Qlik Cloud Spaces and Items APIs and keeps only a case-insensitive exact match; it fails loudly (listing candidate ids) if the name is missing or ambiguous. Personal spaces are not returned by this lookup, so a resource living in one must be targeted by id.

Both tasks support `wait` (default `true`, poll until the run finishes), `pollFrequency` (default `PT10S`), `maxDuration` (default `PT4H`), and `reattach` (default `true`): if the worker restarts mid-poll, or the task is manually restarted, the task reattaches to the run it already triggered (tracked by taskrun id) instead of triggering a duplicate.

- **`apps.Reload`** — triggers a reload via `appId` or `spaceName` + `appName`. Set `partial: true` for a partial reload, `weight` (1-10) to set the queue priority, and `variables` (at most 20 entries, 256 characters each) to pass reload variables to the app's load script. On completion, the full reload log is stored to internal storage (`logUri`) and a bounded tail is logged; a failed, canceled, or exceeded-limit reload fails the task with the Qlik error code/message. A `429` on the trigger call always means a reload is already pending/in progress for the same app (per the Reloads API spec) and fails immediately instead of being retried as a rate limit; a `403` with Qlik code `RELOADS-013` means the tenant's reload frequency quota for the app is used up and also fails immediately, with a message naming the fix. On success, `emitAssets` (default `true`) emits a Custom asset (`io.kestra.plugin.qlikcloud.assets.App`) carrying the app's freshness — a no-op on Kestra OSS, and also a no-op on EE unless `assets: { enableAuto: true }` is set on the task (see below).
- **`automations.RunAutomation`** — triggers a run via `automationId` or `spaceName` + `automationName`. A run that `finished with warnings` counts as a success unless `failOnWarnings` is set. Final statuses are `finished`, `finished with warnings`, `failed`, `stopped`, and `exceeded limit`; `must stop` (a stop was requested but the run hasn't ended yet) keeps polling like any other in-progress status. A failing run's error message is built from the run's `error[]` array. Automation inputs are out of scope: they require the automation's Triggered mode and a per-automation webhook token, not this task.

Both tasks call the equivalent cancel/stop action when the task is killed.

### Asset emission gotcha

`Reload`'s asset is only recorded when **both** are true: the task's own `emitAssets` (default `true`) and Kestra's core `assets.enableAuto` (default `false`, opt-in per task). Without `assets: { enableAuto: true }` on the `Reload` task, the emit call runs but Kestra silently drops it — `emitAssets: true` alone does nothing:

```yaml
- id: reload
  type: io.kestra.plugin.qlikcloud.apps.Reload
  tenantUrl: https://mytenant.eu.qlikcloud.com
  apiKey: "{{ secret('QLIK_API_KEY') }}"
  appId: 60f2e3b1a1b2c3d4e5f6a7b8
  assets:
    enableAuto: true
```

`emitAssets: false` remains a per-task opt-out for when `enableAuto` is set for other reasons but this particular `Reload` should not emit its app asset.
