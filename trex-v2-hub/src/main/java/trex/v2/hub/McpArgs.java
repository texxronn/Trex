package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Reads MCP tool arguments (V2-MCP-SERVER-PLAN.md §3.1, Stage 2). Every accessor names the field it
 * is reading, and a missing required field or a wrong type is a {@link BadArgs} the dispatcher turns
 * into a tool result carrying {@code isError:true} — a tool error is data for the model, never a
 * crashed server (plan §1). Values arrive as a JSON object whose shape each tool's schema declares.
 */
final class McpArgs {

    /** A malformed argument: a missing required field or a wrong type. */
    static final class BadArgs extends RuntimeException {
        BadArgs(String message) {
            super(message);
        }
    }

    private McpArgs() {}

    /** Reject any member a tool's schema does not declare ({@code additionalProperties:false}). */
    static void rejectUnknown(JsonNode args, Set<String> allowed) {
        Iterator<Map.Entry<String, JsonNode>> fields = args.fields();
        while (fields.hasNext()) {
            String name = fields.next().getKey();
            if (!allowed.contains(name)) {
                throw new BadArgs("unknown argument '" + name + "'");
            }
        }
    }

    /** A string field, or null when absent/blank; a non-string is a tool error. */
    static String optionalText(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new BadArgs(name + " must be a string");
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    static String requiredText(JsonNode args, String name) {
        String value = optionalText(args, name);
        if (value == null) {
            throw new BadArgs(name + " is required");
        }
        return value;
    }

    /** An integral JSON number; a string, a fraction or anything else is a type error (schema: integer). */
    static Long optionalLong(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        throw new BadArgs(name + " must be an integer");
    }

    static long requiredLong(JsonNode args, String name) {
        Long value = optionalLong(args, name);
        if (value == null) {
            throw new BadArgs(name + " is required");
        }
        return value;
    }

    static Integer optionalInt(JsonNode args, String name) {
        Long value = optionalLong(args, name);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new BadArgs(name + " is out of range");
        }
        return value.intValue();
    }

    /** A JSON boolean; a string or anything else is a type error (schema: boolean). */
    static Boolean optionalBool(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        throw new BadArgs(name + " must be true or false");
    }

    /** An {@code ISO-8601} date ({@code yyyy-MM-dd}). */
    static LocalDate optionalDate(JsonNode args, String name) {
        String value = optionalText(args, name);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadArgs(name + " must be an ISO date (yyyy-MM-dd)");
        }
    }
}
