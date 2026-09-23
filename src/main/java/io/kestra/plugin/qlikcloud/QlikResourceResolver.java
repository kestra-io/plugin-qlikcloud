package io.kestra.plugin.qlikcloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resolves a Qlik Cloud space and resource (app or automation) name to ids, shared by every task
 * that supports targeting by name instead of id. The `name` filter on both the spaces and items
 * endpoints is a case-insensitive contains/wildcard search with a default page size well below what
 * a busy tenant can have, so every page is walked via {@code links.next.href} before keeping only
 * exact (case-insensitive) matches.
 */
public final class QlikResourceResolver {
    private static final int PAGE_LIMIT = 100;

    private QlikResourceResolver() {
    }

    public record ResolvedResource(String resourceId, String spaceId) {
    }

    public static String resolveSpaceId(QlikCloudClient client, String rSpaceName) throws IOException {
        List<JsonNode> spaces = fetchAllPages(client, "/api/v1/spaces?name=" + QlikCloudClient.encode(rSpaceName) + "&limit=" + PAGE_LIMIT);

        List<JsonNode> exact = spaces.stream()
            .filter(node -> rSpaceName.equalsIgnoreCase(node.path("name").asText("")))
            .toList();

        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                "No Qlik Cloud space named '" + rSpaceName + "' was found. Note that personal spaces are not " +
                    "returned by this lookup; target the resource by id instead if it lives in one."
            );
        }
        if (exact.size() > 1) {
            throw new IllegalArgumentException(
                "Several Qlik Cloud spaces are named '" + rSpaceName + "': " + idList(exact, "id") + ". Target the resource by id instead."
            );
        }

        return exact.getFirst().path("id").asText();
    }

    public static ResolvedResource resolveResourceId(QlikCloudClient client, String spaceId, String rSpaceName, String rName, String resourceType) throws IOException {
        List<JsonNode> items = fetchAllPages(
            client,
            "/api/v1/items?spaceId=" + QlikCloudClient.encode(spaceId) +
                "&name=" + QlikCloudClient.encode(rName) +
                "&resourceType=" + QlikCloudClient.encode(resourceType) +
                "&limit=" + PAGE_LIMIT
        );

        List<JsonNode> exact = items.stream()
            .filter(node -> rName.equalsIgnoreCase(node.path("name").asText("")))
            .toList();

        String spaceDescription = "'" + rSpaceName + "' (" + spaceId + ")";

        if (exact.isEmpty()) {
            throw new IllegalArgumentException("No " + resourceType + " named '" + rName + "' was found in space " + spaceDescription + ".");
        }
        if (exact.size() > 1) {
            throw new IllegalArgumentException(
                "Several " + resourceType + "s are named '" + rName + "' in space " + spaceDescription + ": " +
                    idList(exact, "resourceId") + ". Target the resource by id instead."
            );
        }

        JsonNode match = exact.getFirst();
        return new ResolvedResource(match.path("resourceId").asText(), match.path("spaceId").asText(spaceId));
    }

    /** Fallback lookup for the space of a resource that was targeted directly by id (name-lookup already knows it). */
    public static String resolveSpaceIdForResource(QlikCloudClient client, String resourceId, String resourceType) throws IOException {
        JsonNode items = client.get(
            "/api/v1/items?resourceId=" + QlikCloudClient.encode(resourceId) + "&resourceType=" + QlikCloudClient.encode(resourceType)
        );
        JsonNode data = items.path("data");
        return data.isArray() && !data.isEmpty() ? data.get(0).path("spaceId").asText(null) : null;
    }

    private static List<JsonNode> fetchAllPages(QlikCloudClient client, String firstPathAndQuery) throws IOException {
        List<JsonNode> all = new ArrayList<>();
        String next = firstPathAndQuery;

        while (next != null) {
            JsonNode page = client.get(next);
            page.path("data").forEach(all::add);

            JsonNode href = page.path("links").path("next").path("href");
            next = href.isMissingNode() || href.isNull() || href.asText().isBlank() ? null : href.asText();
        }

        return all;
    }

    private static String idList(List<JsonNode> nodes, String idField) {
        return nodes.stream().map(node -> node.path(idField).asText()).collect(Collectors.joining(", "));
    }
}
