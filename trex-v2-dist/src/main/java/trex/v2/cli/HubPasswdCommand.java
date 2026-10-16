package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.hub.auth.AuthFile;
import trex.v2.hub.auth.PasswordHash;
import trex.v2.log.ConfigLoader;
import trex.v2.log.Yaml;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code trex hub passwd}: create or update the credential file (V2-SPEC.md §9.5,
 * V2-AUTH-PLAN.md §4.2). It runs on the host as root over SSH — the one place a plaintext password
 * is typed and immediately discarded. The file is created with its {@code sessionSecret} on first
 * use; a password may be set only for a declared {@code active} user, checked against
 * {@code users.yaml}; and no hash is ever printed.
 */
@Command(name = "passwd", mixinStandardHelpOptions = true,
    description = "Set a user's password in the credential file, or --list the ids that have one.")
public final class HubPasswdCommand implements Callable<Integer> {

    @Option(names = "--auth", description = "Credential file to create or update (or TREX_AUTH_FILE).")
    Path auth;

    @Option(names = "--config",
        description = "Config directory; required with --user, to check the user is declared and active.")
    Path config;

    @Option(names = "--user", description = "The user id whose password to set.")
    String user;

    @Option(names = "--list", description = "List the ids that have a password, and exit.")
    boolean list;

    @Override
    public Integer call() throws Exception {
        String fromEnv = env("TREX_AUTH_FILE");
        Path authFile = auth != null ? auth : (fromEnv == null ? null : Path.of(fromEnv));
        if (authFile == null) {
            System.err.println("--auth (or TREX_AUTH_FILE) is required");
            return 64;
        }
        if (list) {
            return list(authFile);
        }
        if (user == null || user.isBlank()) {
            System.err.println("--user is required (or --list)");
            return 64;
        }
        String refusal = checkActive(user, config);
        if (refusal != null) {
            System.err.println(refusal);
            return 1;
        }
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String password = readPassword("New password for " + user + ": ", stdin);
        if (password == null || password.isEmpty()) {
            System.err.println("the password is empty; refused");
            return 1;
        }
        String again = readPassword("Repeat password: ", stdin);
        if (!password.equals(again)) {
            System.err.println("the passwords do not match; refused");
            return 1;
        }
        AuthFile file = Files.isRegularFile(authFile) ? AuthFile.load(authFile) : AuthFile.create(authFile);
        file.setHash(user, PasswordHash.hash(password));
        file.save();
        System.out.println("password set for " + user);
        return 0;
    }

    /** {@code --list}: the ids that have a hash, one per line, never a hash; an absent file is empty. */
    private static int list(Path authFile) {
        if (!Files.isRegularFile(authFile)) {
            return 0;
        }
        for (String id : AuthFile.load(authFile).users()) {
            System.out.println(id);
        }
        return 0;
    }

    /**
     * The A-8 rule: a password exists only for a person the config declares and marks {@code active}
     * (V2-SPEC.md §9.5). The credential file lives outside the repo, so it cannot carry that list —
     * {@code --config} names it. {@code agent} is not a person and is never a login (A-8, D4).
     */
    private static String checkActive(String user, Path configDir) {
        if (configDir == null) {
            return "--config is required to check the user against users.yaml";
        }
        List<ConfigLoader.UserEntry> users =
            Yaml.read(configDir.resolve("users.yaml"), ConfigLoader.UsersFile.class).users();
        for (ConfigLoader.UserEntry entry : users == null ? List.<ConfigLoader.UserEntry>of() : users) {
            if (entry.id().equals(user)) {
                return entry.active() ? null
                    : "user '" + user + "' is not active in users.yaml; refused (V2-SPEC.md §9.5)";
            }
        }
        return "user '" + user + "' is not declared in users.yaml; refused (V2-SPEC.md §9.5)";
    }

    /**
     * Never echo: a console reads the password with echo off. With no console (a pipe, or a test)
     * stdin is the only source and there is nothing for the process to echo — interactive use always
     * has a console, which is where the guarantee matters.
     */
    private static String readPassword(String prompt, BufferedReader stdin) throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("%s", prompt);
            return chars == null ? null : new String(chars);
        }
        System.out.print(prompt);
        System.out.flush();
        return stdin.readLine();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
