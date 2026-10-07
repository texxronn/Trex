package trex.v2.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A job the runner offers (V2-PROPOSAL.md §5.5). It carries its own parameter schema and a builder
 * that turns validated parameters into child steps. The runner never contains business logic: a job
 * is a named invocation of an existing subcommand.
 *
 * @param capability {@code read} (plan/verify/ingest-report) or {@code write} (apply)
 */
public record JobSpec(String name, String title, String description, String capability,
                      List<JobParam> params, Steps steps) {

    /** Turn the request parameters into one or more child steps; refuse anything malformed. */
    @FunctionalInterface
    public interface Steps {
        List<Step> build(JsonNode params);
    }
}
