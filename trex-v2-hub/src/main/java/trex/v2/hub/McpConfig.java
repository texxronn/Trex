package trex.v2.hub;

/**
 * The MCP write gate and attribution (V2-MCP-SERVER-PLAN.md §3.2, D4): the write tools are absent
 * unless the operator opts in, and every write is stamped with {@link #agentUser()} — a distinct,
 * permanent identity, never the person's. Read tools need no gate — the hub is loopback-only and
 * reads nothing that the UI does not already serve.
 */
public record McpConfig(boolean allowWrites, String agentUser) {

    /** The default identity an agent writes as; {@code TREX_MCP_AGENT_USER} overrides it. */
    public static final String DEFAULT_AGENT_USER = "agent";

    /** Opt out of writes, attributed to the default agent (used by the read-only callers/tests). */
    public McpConfig(boolean allowWrites) {
        this(allowWrites, DEFAULT_AGENT_USER);
    }

    /** Truthy is {@code 1} or {@code true} (case-insensitive); anything else — unset included — is off. */
    public static McpConfig fromEnv() {
        String raw = System.getenv("TREX_MCP_ALLOW_WRITES");
        boolean allow = raw != null && ("1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()));
        String agent = System.getenv("TREX_MCP_AGENT_USER");
        if (agent == null || agent.isBlank()) {
            agent = DEFAULT_AGENT_USER;
        }
        return new McpConfig(allow, agent);
    }
}
