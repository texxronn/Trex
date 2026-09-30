package trex.v2.runner;

import java.nio.file.Path;
import java.time.Duration;

/**
 * What the runner needs (V2-PROPOSAL.md §5.5): where config, statements and staging live, the
 * URLs each job needs, the token the hub presents, whether {@code --apply} is unlocked, and how
 * long a job may run. Config is read only to resolve a staged file's default type; the runner
 * never writes it.
 */
public record RunnerConfig(
    String host,
    int port,
    Path configDir,
    Path statementsDir,
    Path stagingDir,
    Path evidenceDir,
    Path archive,
    String sequencerUrl,
    String hubUrl,
    String fireflyUrl,
    Path accountsFile,
    String token,
    boolean allowApply,
    Duration jobTimeout) {

    public static final int DEFAULT_PORT = 8091;

    public RunnerConfig {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (configDir == null) {
            throw new IllegalArgumentException("configDir is required");
        }
        if (stagingDir == null) {
            throw new IllegalArgumentException("stagingDir is required");
        }
        if (evidenceDir == null) {
            throw new IllegalArgumentException("evidenceDir is required");
        }
        if (jobTimeout == null) {
            jobTimeout = Duration.ofMinutes(30);
        }
    }
}
