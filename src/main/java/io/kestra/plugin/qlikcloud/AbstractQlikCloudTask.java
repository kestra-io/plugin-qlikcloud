package io.kestra.plugin.qlikcloud;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public abstract class AbstractQlikCloudTask extends Task {
    @Schema(
        title = "Qlik Cloud tenant URL",
        description = "The tenant root URL, e.g. `https://mytenant.eu.qlikcloud.com`. Do not append `/api/v1`."
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> tenantUrl;

    @Schema(
        title = "API key",
        description = """
            A Qlik Cloud API key, or a pre-obtained OAuth 2.0 access token: both are sent as-is in the \
            `Authorization: Bearer` header. Generate an API key from the tenant's Management Console \
            (Identity & access > API keys)."""
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> apiKey;

    protected QlikCloudClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        String rTenantUrl = runContext.render(this.tenantUrl).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Missing required `tenantUrl` property"));
        String rApiKey = runContext.render(this.apiKey).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Missing required `apiKey` property"));

        return QlikCloudClient.of(runContext, rTenantUrl, rApiKey);
    }
}
