package trex.v2.runner;

import java.util.List;

/**
 * One input a job accepts, described so the UI can render a control without knowing the job. The
 * runner validates against this and refuses anything else — there is no free-form argv.
 */
public record JobParam(String name, String type, boolean required, List<String> allowed, String description) {

    public static JobParam of(String name, String type, boolean required, String description) {
        return new JobParam(name, type, required, List.of(), description);
    }

    public static JobParam choice(String name, List<String> allowed, String description) {
        return new JobParam(name, "choice", true, allowed, description);
    }
}
