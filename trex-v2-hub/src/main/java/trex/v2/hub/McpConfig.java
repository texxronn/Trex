package trex.v2.hub;

/**
 * The MCP write gate (V2-MCP-SERVER-PLAN.md §3.2, D3/D4): the write tools are absent unless the
 * operator opts in. Read tools need no gate — the hub is loopback-only and reads nothing that the
 * UI does not already serve.
 */
public record McpConfig(boolean allowWrites) {

    /** Truthy is {@code 1} or {@code true} (case-insensitive); anything else — unset included — is off. */
    public static McpConfig fromEnv() {
        String raw = System.getenv("TREX_MCP_ALLOW_WRITES");
        boolean allow = raw != null && ("1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()));
        return new McpConfig(allow);
    }
}
