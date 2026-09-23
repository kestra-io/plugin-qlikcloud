package io.kestra.plugin.qlikcloud;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 28281)
class QlikResourceResolverTest {
    @Inject
    private RunContextFactory runContextFactory;

    private QlikCloudClient client(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());
        return QlikCloudClient.of(runContext, wireMockRuntimeInfo.getHttpBaseUrl(), "test-key");
    }

    @Test
    void resolvesExactCaseInsensitiveSpaceMatch(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/v1/spaces"))
                .willReturn(okJson("""
                    {"data": [{"id": "space-1", "name": "Sales Analytics"}], "links": {}}
                    """))
        );

        String spaceId = QlikResourceResolver.resolveSpaceId(client(wireMockRuntimeInfo), "sales analytics");

        assertThat(spaceId, is("space-1"));
    }

    @Test
    void failsWhenNoSpaceMatchesExactly(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/v1/spaces"))
                .willReturn(okJson("""
                    {"data": [{"id": "space-1", "name": "Sales Analytics Extended"}], "links": {}}
                    """))
        );

        QlikCloudClient client = client(wireMockRuntimeInfo);
        assertThrows(IllegalArgumentException.class, () -> QlikResourceResolver.resolveSpaceId(client, "Sales Analytics"));
    }

    @Test
    void failsWhenSeveralSpacesMatchExactly(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/v1/spaces"))
                .willReturn(okJson("""
                    {"data": [{"id": "space-1", "name": "Sales"}, {"id": "space-2", "name": "SALES"}], "links": {}}
                    """))
        );

        QlikCloudClient client = client(wireMockRuntimeInfo);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> QlikResourceResolver.resolveSpaceId(client, "Sales"));
        assertThat(e.getMessage().contains("space-1") && e.getMessage().contains("space-2"), is(true));
    }

    @Test
    void paginatesThroughLinksNextToFindTheSpace(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/v1/spaces"))
                .withQueryParam("name", equalTo("Sales Analytics"))
                .willReturn(okJson("""
                    {"data": [{"id": "space-0", "name": "Sales Analytics Old"}], "links": {"next": {"href": "%s/api/v1/spaces?page=2"}}}
                    """.formatted(wireMockRuntimeInfo.getHttpBaseUrl())))
        );
        stubFor(
            get(urlPathEqualTo("/api/v1/spaces"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(okJson("""
                    {"data": [{"id": "space-1", "name": "Sales Analytics"}], "links": {}}
                    """))
        );

        String spaceId = QlikResourceResolver.resolveSpaceId(client(wireMockRuntimeInfo), "Sales Analytics");

        assertThat(spaceId, is("space-1"));
        verify(getRequestedFor(urlPathEqualTo("/api/v1/spaces")).withQueryParam("page", equalTo("2")));
    }

    @Test
    void resolvesResourceIdUsingItemsResourceIdField(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/v1/items"))
                .willReturn(okJson("""
                    {"data": [{"resourceId": "app-1", "name": "Sales Dashboard", "spaceId": "space-1"}], "links": {}}
                    """))
        );

        QlikResourceResolver.ResolvedResource resolved = QlikResourceResolver.resolveResourceId(client(wireMockRuntimeInfo), "space-1", "Sales Dashboard", "app");

        assertThat(resolved.resourceId(), is("app-1"));
        assertThat(resolved.spaceId(), is("space-1"));
    }

    @Test
    void failsWhenSeveralItemsMatchExactly(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            get(urlPathEqualTo("/api/v1/items"))
                .willReturn(okJson("""
                    {"data": [{"resourceId": "app-1", "name": "Dashboard", "spaceId": "space-1"}, {"resourceId": "app-2", "name": "Dashboard", "spaceId": "space-1"}], "links": {}}
                    """))
        );

        QlikCloudClient client = client(wireMockRuntimeInfo);
        assertThrows(IllegalArgumentException.class, () -> QlikResourceResolver.resolveResourceId(client, "space-1", "Dashboard", "app"));
    }
}
