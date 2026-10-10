package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.log.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP resources (V2-MCP-SERVER-PLAN.md §3.3, Stage 3): read-only snapshots a client can fetch by
 * URI. {@code trex://status} and {@code trex://reference} are JSON renderings of the hub's own
 * reads; {@code trex://config/*} are the live YAML of the seeded config files the hub exposes. Every
 * read goes through {@link HubApi}; this class adds no writer and reaches neither the journal nor the
 * index.
 *
 * <p>A missing config file is an empty resource, not an error — the file simply has not been seeded
 * yet. An unknown URI is MCP's {@code -32002} (resource not found), which {@link McpApi} turns into
 * the JSON-RPC error.
 */
final class McpResources {

    /** One exposed resource: the URI, its display name, an optional description and its MIME type. */
    private record Def(String uri, String name, String description, String mimeType, String file) {}

    private static final String JSON = "application/json";
    private static final String YAML = "text/yaml";

    /** The resources, in the stable order {@code resources/list} publishes. */
    private static final Map<String, Def> DEFS = defs();

    private McpResources() {}

    /** The {@code resources/list} result: every exposed resource, name and MIME type. */
    static JsonNode list() {
        ObjectNode result = Json.mapper().createObjectNode();
        ArrayNode resources = result.putArray("resources");
        for (Def def : DEFS.values()) {
            ObjectNode node = resources.addObject();
            node.put("uri", def.uri());
            node.put("name", def.name());
            if (def.description() != null) {
                node.put("description", def.description());
            }
            node.put("mimeType", def.mimeType());
        }
        return result;
    }

    /**
     * The {@code resources/read} result: one {@code contents} entry carrying the URI, its MIME type
     * and the text. Throws {@link NotFound} for an unknown URI; a config file that is absent is an
     * empty text.
     */
    static JsonNode read(HubApi api, String uri) {
        Def def = DEFS.get(uri);
        if (def == null) {
            throw new NotFound(uri);
        }
        ObjectNode result = Json.mapper().createObjectNode();
        ObjectNode content = result.putArray("contents").addObject();
        content.put("uri", uri);
        content.put("mimeType", def.mimeType());
        content.put("text", text(api, def));
        return result;
    }

    private static String text(HubApi api, Def def) {
        if ("trex://status".equals(def.uri())) {
            return compact(McpTools.status(api, Json.mapper().createObjectNode()));
        }
        if ("trex://reference".equals(def.uri())) {
            return compact(Json.mapper().valueToTree(api.refdata()));
        }
        // A config resource: the live file, or "" when it has not been seeded.
        Optional<String> file = api.configFile(def.file());
        return file.orElse("");
    }

    private static Map<String, Def> defs() {
        Map<String, Def> map = new LinkedHashMap<>();
        add(map, new Def("trex://status", "Trex status",
            "Hub status and head (asOfN, deriveVersion, counts)", JSON, null));
        add(map, new Def("trex://reference", "Trex reference",
            "Reference data: accounts, users and categories", JSON, null));
        add(map, new Def("trex://config/accounts", "accounts.yaml",
            "The live accounts configuration", YAML, "accounts.yaml"));
        add(map, new Def("trex://config/categories", "categories.yaml",
            "The live categories configuration", YAML, "categories.yaml"));
        add(map, new Def("trex://config/transfers", "transfers.yaml",
            "The live transfers configuration", YAML, "transfers.yaml"));
        add(map, new Def("trex://config/firefly", "firefly.yaml",
            "The live firefly configuration", YAML, "firefly.yaml"));
        add(map, new Def("trex://config/profiles", "profiles.yaml",
            "The live profiles configuration", YAML, "profiles.yaml"));
        return java.util.Collections.unmodifiableMap(map);
    }

    private static void add(Map<String, Def> map, Def def) {
        map.put(def.uri(), def);
    }

    private static String compact(JsonNode data) {
        try {
            return Json.mapper().writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + data, e);
        }
    }

    /** An unknown resource URI: MCP's resource-not-found, mapped to {@code -32002} by the dispatcher. */
    static final class NotFound extends RuntimeException {
        NotFound(String uri) {
            super("Resource not found: " + uri);
        }
    }
}
