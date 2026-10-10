package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.log.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP prompts (V2-MCP-SERVER-PLAN.md §3.4, Stage 3): four static recipes that steer a client's
 * model to the read tools and, where a conclusion is the point, to a {@code trex_submit_decisions}
 * draft. The text is constant — no hub call, no writer — so {@code prompts/get} is pure and cannot
 * disturb the log. An unknown name is {@code -32602} (invalid params), which {@link McpApi} maps.
 */
final class McpPrompts {

    /** One prompt argument, as {@code prompts/list} publishes it. */
    record Arg(String name, String description, boolean required) {}

    /** One prompt: its name, description, arguments and the message text it returns. */
    record Prompt(String name, String description, List<Arg> arguments, String text) {}

    private static final Map<String, Prompt> PROMPTS = prompts();

    private McpPrompts() {}

    /** The {@code prompts/list} result: every prompt with its arguments, in a stable order. */
    static JsonNode list() {
        ObjectNode result = Json.mapper().createObjectNode();
        ArrayNode prompts = result.putArray("prompts");
        for (Prompt prompt : PROMPTS.values()) {
            ObjectNode node = prompts.addObject();
            node.put("name", prompt.name());
            node.put("description", prompt.description());
            ArrayNode args = node.putArray("arguments");
            for (Arg arg : prompt.arguments()) {
                ObjectNode argNode = args.addObject();
                argNode.put("name", arg.name());
                argNode.put("description", arg.description());
                if (arg.required()) {
                    argNode.put("required", true);
                }
            }
        }
        return result;
    }

    /**
     * The {@code prompts/get} result: the description and one user message. Throws
     * {@link UnknownPrompt} for a name that is not one of the four; {@code arguments} is accepted but
     * not needed, because the text is static.
     */
    static JsonNode get(String name, JsonNode arguments) {
        Prompt prompt = PROMPTS.get(name);
        if (prompt == null) {
            throw new UnknownPrompt(name);
        }
        ObjectNode result = Json.mapper().createObjectNode();
        result.put("description", prompt.description());
        ObjectNode message = result.putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", prompt.text());
        return result;
    }

    private static Map<String, Prompt> prompts() {
        Map<String, Prompt> map = new LinkedHashMap<>();
        map.put("monthly_review", new Prompt("monthly_review",
            "Review a month of activity and surface what needs attention",
            List.of(new Arg("month", "The month to review, as YYYY-MM (defaults to the current month)", false)),
            "Review this month's finances with Trex. Use the read tools: trex_status for the current "
                + "head, trex_units, trex_ledger over the month, trex_review for the derived review "
                + "queue, trex_commitments and trex_expected for upcoming obligations, and "
                + "trex_ingests to see what was imported. Summarise what changed, what is "
                + "uncategorised and what looks anomalous. This prompt is read-only: propose nothing "
                + "to write."));
        map.put("classify_transaction", new Prompt("classify_transaction",
            "Propose a category for one transaction",
            List.of(new Arg("externalId", "The external id of the transaction to classify", true)),
            "Classify one transaction. Read it with trex_ledger and trex_notes using its "
                + "externalId, and read trex_workbook for the existing rules, pins and suggestions. "
                + "Decide a category, then propose it as a draft for trex_submit_decisions. Do not "
                + "write directly and do not invent an externalId."));
        map.put("reconcile_account", new Prompt("reconcile_account",
            "Reconcile one account and propose the fixing decisions",
            List.of(new Arg("account", "The account ref to reconcile", true)),
            "Reconcile one account. Use the read tools: trex_accounts, trex_opening, "
                + "trex_reconcile, trex_chains and trex_transfers, plus trex_ledger for the "
                + "account. Identify unbalanced chains, missing openings and unpaired transfers, "
                + "then propose the decisions that fix them (for example OPENING, LINK or DISMISS) "
                + "as a draft for trex_submit_decisions. Do not write directly."));
        map.put("mark_paid", new Prompt("mark_paid",
            "Match a commitment occurrence to a transaction and propose it as paid",
            List.of(new Arg("commitment", "The commitment id whose occurrence was paid", true)),
            "Mark a commitment occurrence as paid. Read trex_commitments and "
                + "trex_commitment_activity for the commitment, and trex_expected for the window, "
                + "then find the matching ledger transaction with trex_ledger. Propose a PAY "
                + "decision, naming the occurrence and the matched transaction, as a draft for "
                + "trex_submit_decisions. Do not write directly."));
        return java.util.Collections.unmodifiableMap(map);
    }

    /** An unknown prompt name: {@code -32602}, mapped by the dispatcher. */
    static final class UnknownPrompt extends RuntimeException {
        UnknownPrompt(String name) {
            super("Unknown prompt: " + name);
        }
    }
}
