package trex.v2.runner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a job by re-launching the same artifact: the one jar, the one role per command
 * (V2-PROPOSAL.md §5.5). Every step is an ordinary {@code trex <role> …} a person could type, with
 * the runner's own classpath, so the child sees exactly what the parent sees.
 */
public final class ProcessCommandRunner implements CommandRunner {

    private final String javaBin;
    private final String classpath;

    public ProcessCommandRunner() {
        this(System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java",
            System.getProperty("java.class.path"));
    }

    public ProcessCommandRunner(String javaBin, String classpath) {
        this.javaBin = javaBin;
        this.classpath = classpath;
    }

    @Override
    public int run(List<String> argv, Consumer<String> line, Consumer<Process> handle) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        if (classpath != null && !classpath.isBlank()) {
            command.add("-cp");
            command.add(classpath);
        }
        command.add("trex.v2.Main");
        command.addAll(argv);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        handle.accept(process);
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = reader.readLine()) != null) {
                line.accept(l);
            }
        }
        return process.waitFor();
    }
}
