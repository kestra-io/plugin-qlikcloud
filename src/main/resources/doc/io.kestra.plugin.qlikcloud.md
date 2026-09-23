# How to use the Qlik Cloud plugin

Trigger and monitor [Qlik Cloud](https://qlik.dev/) app reloads and Qlik Automate automation runs from a Kestra flow.

## Authentication

Every task requires `tenantUrl` (the tenant root, e.g. `https://mytenant.eu.qlikcloud.com` — do not append `/api/v1`) and `apiKey`, sent as a bearer token. `apiKey` accepts either a Qlik Cloud [API key](https://qlik.dev/authenticate/api-key/generate-your-first-api-key/) or a pre-obtained OAuth 2.0 access token; this plugin does not implement the OAuth flow itself. Generate an API key from the tenant's Management Console (Identity & access > API keys), store it as a [secret](https://kestra.io/docs/concepts/secret), and reference it with `{{ secret('QLIK_API_KEY') }}`. You can also set `tenantUrl` and `apiKey` once as [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) for every Qlik Cloud task in a namespace.

## Tasks

Both tasks below target their resource *either* by id *or* by `spaceName` together with the resource's name — exactly one of the two must be set. Name lookup paginates through the Qlik Cloud Spaces and Items APIs and keeps only a case-insensitive exact match; it fails loudly (listing candidate ids) if the name is missing or ambiguous. Personal spaces are not returned by this lookup, so a resource living in one must be targeted by id.

Both tasks support `wait` (default `true`, poll until the run finishes), `pollFrequency` (default `PT10S`), `maxDuration` (default `PT4H`), and `reattach` (default `true`): if the worker restarts mid-poll, or the task is manually restarted, the task reattaches to the run it already triggered (tracked by taskrun id) instead of triggering a duplicate.

- **`apps.Reload`** — triggers a reload via `appId` or `spaceName` + `appName`. Set `partial: true` for a partial reload, `weight` (1-10) to set the queue priority, and `variables` (at most 20 entries, 256 characters each) to pass reload variables to the app's load script. On completion, the full reload log is stored to internal storage (`logUri`) and a bounded tail is logged; a failed, canceled, or exceeded-limit reload fails the task with the Qlik error code/message. On success, `emitAssets` (default `true`) emits a Custom asset (`io.kestra.plugin.qlikcloud.assets.App`) carrying the app's freshness — a no-op on Kestra OSS.
- **`automations.RunAutomation`** — triggers a run via `automationId` or `spaceName` + `automationName`. A run that `finished with warnings` counts as a success unless `failOnWarnings` is set. Automation inputs are out of scope: they require the automation's Triggered mode and a per-automation webhook token, not this task.

Both tasks call the equivalent cancel/stop action when the task is killed.
