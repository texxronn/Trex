package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.CashRequest;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.hub.api.LoginRequest;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.ReflowRequest;
import trex.v2.hub.api.TransferRequest;
import trex.v2.hub.auth.Auth;
import trex.v2.hub.auth.Session;
import trex.v2.log.Json;
import trex.v2.sequencer.api.DecisionDraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The hub's read and decision API (V2-PROPOSAL.md §7.4, §10, §6.6). Handlers run on virtual
 * threads; reads go through the pool, writes are forwarded to the only writer.
 *
 * <p>With sign-in configured (V2-SPEC.md §9.5) every context and route goes through one
 * {@link #gated} wrapper — a single check, never a check inside each handler. A null {@link Auth}
 * is sign-in off: {@code gated} passes everything and the surface is today's hub plus the
 * {@code /api/whoami} the shell needs to name a default acting user (§9.5). {@code /mcp} is
 * deliberately left outside the wrapper (A-4).
 */
final class HubHttpApi {

    private static final Logger log = LoggerFactory.getLogger(HubHttpApi.class);

    /** The A-9 failure delay in milliseconds: the same for every refusal, with no lockout. */
    private static final long SIGN_IN_REFUSAL_MS = 250;

    /**
     * Where {@link #gated} parks the resolved session user for the write handlers (V2-SPEC.md §9.5).
     * An attribute, not a parameter, because the routes share one lambda shape and only some of
     * them stamp; absent means sign-in is off and the request's own {@code user} still rules.
     */
    private static final String SESSION_USER = "trex.session.user";

    private final Auth auth;

    private HubHttpApi(Auth auth) {
        this.auth = auth;
    }

    static HttpServer start(String host, int port, HubApi api, HubEvents events, RunnerClient runner,
                            Auth auth) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        new HubHttpApi(auth).register(server, api, events, runner);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private void register(HttpServer server, HubApi api, HubEvents events, RunnerClient runner) {
        context(server, "/api/events", ex -> streamEvents(ex, api, events));
        context(server, "/api/jobs", ex -> jobs(ex, api, runner));
        // A-4: /mcp stays outside the wrapper. It inherits the listener, so an anonymous caller on
        // the network can still read and (only with TREX_MCP_ALLOW_WRITES) write as `agent`. A test
        // pins this so turning it on later is a visible spec change, not a quiet refactor.
        server.createContext("/mcp", ex -> McpHttp.handle(ex, api, McpConfig.fromEnv()));
        route(server, "/head", "GET", ex -> write(ex, 200, api.head()));
        route(server, "/api/status", "GET", ex -> write(ex, 200, api.status()));
        route(server, "/api/brief", "GET", ex -> write(ex, 200, api.brief()));
        route(server, "/api/refdata", "GET", ex -> write(ex, 200, api.refdata()));
        route(server, "/api/ledger", "GET", ex -> {
            try {
                write(ex, 200, api.ledger(BlotterQuery.parse(ex.getRequestURI().getQuery())));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
        });
        route(server, "/api/review", "GET", ex -> {
            String query = ex.getRequestURI().getQuery();
            write(ex, 200, api.review(param(query, "kind"), param(query, "account")));
        });
        route(server, "/api/commitments", "GET", ex -> write(ex, 200, api.commitments()));
        route(server, "/api/commitments/activity", "GET", ex -> {
            String id = param(ex.getRequestURI().getQuery(), "id");
            if (id == null || id.isBlank()) {
                sendError(ex, 422, "id is required");
                return;
            }
            write(ex, 200, api.activity(id));
        });
        route(server, "/api/expected", "GET", ex -> {
            try {
                String query = ex.getRequestURI().getQuery();
                String asOf = param(query, "asOf");
                write(ex, 200, api.expected(param(query, "window"),
                    asOf == null ? null : java.time.LocalDate.parse(asOf)));
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                sendError(ex, 422, e.getMessage());
            }
        });
        route(server, "/api/cashflow", "GET", ex -> {
            try {
                String query = ex.getRequestURI().getQuery();
                write(ex, 200, api.cashflow(param(query, "month"), param(query, "view"),
                    present(query, "exclude")));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
        });
        route(server, "/api/transfers", "GET", ex -> write(ex, 200, api.transfers()));
        route(server, "/api/notes", "GET",
            ex -> write(ex, 200, api.notes(param(ex.getRequestURI().getQuery(), "externalId"))));
        route(server, "/api/dismissals", "GET", ex -> write(ex, 200, api.dismissals()));
        route(server, "/api/units", "GET", ex -> write(ex, 200, api.units()));
        route(server, "/api/reconcile", "GET", ex -> write(ex, 200, api.reconcile()));
        route(server, "/api/chains", "GET",
            ex -> write(ex, 200, api.chains(param(ex.getRequestURI().getQuery(), "account"))));
        route(server, "/api/opening", "GET", ex -> write(ex, 200, api.opening()));
        route(server, "/api/workbook", "GET", ex -> write(ex, 200, api.workbook()));
        route(server, "/api/ingests", "GET", ex -> {
            try {
                String sinceN = param(ex.getRequestURI().getQuery(), "sinceN");
                write(ex, 200, api.ingests(sinceN == null ? null : Long.valueOf(sinceN)));
            } catch (NumberFormatException e) {
                sendError(ex, 422, "sinceN must be a number");
            }
        });
        route(server, "/api/since", "GET", ex -> {
            try {
                String query = ex.getRequestURI().getQuery();
                String n = param(query, "n");
                if (n == null) {
                    sendError(ex, 422, "n is required");
                    return;
                }
                // V2-SPEC.md §9.5: with a session the signed-in user decides whose "since you last
                // cleared" line is read, so the `user` parameter is ignored; with sign-in off it is
                // still the identity, exactly as before (A-7).
                String session = sessionUser(ex);
                write(ex, 200, api.since(Long.parseLong(n),
                    session != null ? session : param(query, "user")));
            } catch (NumberFormatException e) {
                sendError(ex, 422, "n must be a number");
            }
        });
        route(server, "/api/accounts", "GET", ex -> {
            try {
                String query = ex.getRequestURI().getQuery();
                String asOf = param(query, "asOf");
                write(ex, 200, api.accounts(param(query, "window"), param(query, "granularity"),
                    asOf == null ? null : java.time.LocalDate.parse(asOf)));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
        });
        Map<String, Handler> projection = new LinkedHashMap<>();
        projection.put("GET", ex -> write(ex, 200, api.projection()));
        projection.put("POST", ex -> {
            try {
                trex.v2.hub.api.ProjectionRequest request = Json.mapper().readValue(readBody(ex),
                    trex.v2.hub.api.ProjectionRequest.class);
                DecisionOutcome outcome = api.putProjection(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/projection", projection);
        Map<String, Handler> cursors = new LinkedHashMap<>();
        cursors.put("GET", ex -> write(ex, 200, api.cursors()));
        cursors.put("POST", ex -> {
            try {
                trex.v2.hub.api.CursorRequest request = Json.mapper().readValue(readBody(ex),
                    trex.v2.hub.api.CursorRequest.class);
                DecisionOutcome outcome = api.putCursors(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/cursors", cursors);
        route(server, "/api/decisions", "POST", ex -> {
            try {
                DecisionRequest request = Json.mapper().readValue(readBody(ex), DecisionRequest.class);
                String session = sessionUser(ex);
                if (session != null && request.decisions() != null) {
                    // V2-SPEC.md §9.5: every draft is stamped with the session's user, and a draft
                    // that names a different one is refused — never silently rewritten — and nothing
                    // reaches the writer. Sign-in off leaves the batch exactly as sent (A-7).
                    String foreign = foreignUser(request, session);
                    if (foreign != null) {
                        sendError(ex, 403, "a draft names '" + foreign
                            + "', but the session is signed in as '" + session + "'");
                        return;
                    }
                    request = new DecisionRequest(request.allOrNone(), request.asOfN(),
                        stampDecisions(request.decisions(), session));
                }
                DecisionOutcome outcome = api.submitDecisions(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/cash", "POST", ex -> {
            try {
                CashRequest request = Json.mapper().readValue(readBody(ex), CashRequest.class);
                String session = sessionUser(ex);
                if (session != null) {
                    // V2-SPEC.md §9.5: the session signs the PIN; any body user is ignored.
                    request = new CashRequest(request.account(), request.date(), request.amount(),
                        request.balance(), request.description(), request.receipt(), request.category(),
                        session);
                }
                DecisionOutcome outcome = api.submitCash(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/cash/months", "GET",
            ex -> write(ex, 200, api.cashMonths(param(ex.getRequestURI().getQuery(), "account"))));

        Map<String, Handler> acks = new LinkedHashMap<>();
        acks.put("GET", ex -> write(ex, 200, api.acks()));
        acks.put("POST", ex -> {
            try {
                AckRequest request = Json.mapper().readValue(readBody(ex), AckRequest.class);
                String session = sessionUser(ex);
                if (session != null) {
                    // V2-SPEC.md §9.5: a signed-in POST signs the session's user; any body user is
                    // ignored. Sign-in off uses the body's user, exactly as before (A-7).
                    request = new AckRequest(session, request.externalId(), request.action(),
                        request.comment());
                }
                DecisionOutcome outcome = api.postAck(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/acks", acks);
        route(server, "/api/eyeball", "GET", ex -> {
            try {
                String query = ex.getRequestURI().getQuery();
                String period = param(query, "period");
                String user = param(query, "user");
                String asOf = param(query, "asOf");
                String bucket = param(query, "bucket");
                write(ex, 200, api.eyeball(period, user,
                    asOf == null ? null : java.time.LocalDate.parse(asOf), bucket));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
        });
        route(server, "/api/reflow/preview", "POST", ex -> {
            try {
                ReflowRequest request = Json.mapper().readValue(readBody(ex), ReflowRequest.class);
                DecisionOutcome outcome = api.reflowPreview(request.categories());
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        Map<String, Handler> configCategories = new LinkedHashMap<>();
        configCategories.put("GET", ex -> {
            Optional<String> yaml = api.categoriesYaml();
            if (yaml.isEmpty()) {
                sendError(ex, 404, "no categories.yaml");
            } else {
                writeText(ex, 200, "text/yaml", yaml.get());
            }
        });
        configCategories.put("PUT", ex -> {
            try {
                ReflowRequest request = Json.mapper().readValue(readBody(ex), ReflowRequest.class);
                DecisionOutcome outcome = api.saveCategories(request.categories());
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/config/categories", configCategories);

        route(server, "/api/reflow/preview/transfers", "POST", ex -> {
            try {
                TransferRequest request = Json.mapper().readValue(readBody(ex), TransferRequest.class);
                DecisionOutcome outcome = api.transfersPreview(request.transfers());
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        Map<String, Handler> configTransfers = new LinkedHashMap<>();
        configTransfers.put("GET", ex -> {
            Optional<String> yaml = api.transfersYaml();
            if (yaml.isEmpty()) {
                sendError(ex, 404, "no transfers.yaml");
            } else {
                writeText(ex, 200, "text/yaml", yaml.get());
            }
        });
        configTransfers.put("PUT", ex -> {
            try {
                TransferRequest request = Json.mapper().readValue(readBody(ex), TransferRequest.class);
                DecisionOutcome outcome = api.saveTransfers(request.transfers());
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/config/transfers", configTransfers);
        route(server, "/api/config/drift", "GET", ex -> write(ex, 200, api.configDrift()));

        if (auth != null) {
            // The open routes (V2-SPEC.md §9.5): signing in or out is meaningless without a
            // credential file, so they exist only with --auth.
            routeOpen(server, "/api/login", "POST", ex -> login(ex, api));
            routeOpen(server, "/api/logout", "POST", ex -> logout(ex));
        }
        // whoami is the shell's first call in both modes (§9.1, §9.5): with --auth `gated` has
        // already refused an anonymous caller, and with sign-in off it answers the default acting
        // user. Registering it outside the block is what lets the sign-in-off shell name someone.
        route(server, "/api/whoami", "GET", ex -> whoami(ex, api));

        // The catch-all: /login (open) and the UI, plus every other path's 404. An anonymous
        // browser request is redirected to the form by `gated`, never shown JSON.
        context(server, "/", ex -> serveStaticOrNotFound(ex));
    }

    /** Static UI assets under {@code /ui/}, the index at {@code /}, the login page at {@code /login}. */
    private void serveStaticOrNotFound(HttpExchange ex) {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendError(ex, 405, "method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/login") && auth != null) {
                // The sign-in page is open (V2-SPEC.md §9.5) and self-contained: nothing under
                // /ui/ needs to be reachable before sign-in.
                serveStatic(ex, "login.html");
            } else if (path.equals("/") || path.equals("/index.html")) {
                serveStatic(ex, "index.html");
            } else if (path.startsWith("/ui/")) {
                serveStatic(ex, path.substring("/ui/".length()));
            } else {
                sendError(ex, 404, "not found");
            }
        } catch (Exception e) {
            log.error("500 static: unhandled failure", e);
            sendError(ex, 500, "internal error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private static void serveStatic(HttpExchange ex, String relative) throws IOException {
        if (relative.contains("..") || relative.startsWith("/") || relative.isBlank()) {
            sendError(ex, 404, "not found");
            return;
        }
        byte[] bytes = fromWebDir(relative);
        if (bytes == null) {
            try (InputStream in = HubHttpApi.class.getResourceAsStream("/trex/v2/hub/web/" + relative)) {
                if (in == null) {
                    sendError(ex, 404, "not found");
                    return;
                }
                bytes = in.readAllBytes();
            }
        }
        ex.getResponseHeaders().set("Content-Type", contentType(relative));
        // The UI is small and changes with the image; revalidate so a redeploy is not
        // masked by a cached module.
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Dev hook: when {@code -Dtrex.hub.webDir} names a directory, serve the UI from it, so a CSS or
     * JS edit is a reload rather than a rebuild. Unset in production, where the resources come from
     * the jar; a file missing from the directory falls back to the jar.
     */
    private static byte[] fromWebDir(String relative) throws IOException {
        String dir = System.getProperty("trex.hub.webDir");
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path root = Path.of(dir).normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // ---- sign-in gate (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.4, §4.5) -------------------------

    /**
     * The one enforcement point. A gated context or route calls this first; it returns true when
     * the request may proceed, and when it may not the rejection (a 401 for the API, a 302 to the
     * form for the browser) has already been sent. A null {@link Auth} is sign-in off, so this is a
     * no-op and the class behaves exactly as it did before sign-in existed.
     */
    private boolean gated(HttpExchange ex) {
        if (auth == null) {
            return true;
        }
        String path = ex.getRequestURI().getPath();
        if (isOpen(path, ex.getRequestMethod())) {
            return true;
        }
        long now = Instant.now().getEpochSecond();
        Session.Principal principal = auth.resolve(ex.getRequestHeaders().getFirst("Cookie"), now);
        if (principal == null) {
            rejectAnonymous(ex, path);
            return false;
        }
        // Park the resolved user for the write handlers to stamp (V2-SPEC.md §9.5); resolved once,
        // at the gate, never again per handler.
        ex.setAttribute(SESSION_USER, principal.user());
        if (principal.shouldSlide(now)) {
            // A-5: a verified cookie past its half-life is re-issued, so an active session slides.
            ex.getResponseHeaders().add("Set-Cookie", auth.session().cookie(principal.user(), now));
        }
        return true;
    }

    /** The only paths that do not require a session (V2-SPEC.md §9.5, A-4 leaves /mcp outside). */
    private static boolean isOpen(String path, String method) {
        return path.equals("/login")
            || (path.equals("/api/login") && "POST".equals(method))
            || (path.equals("/api/logout") && "POST".equals(method));
    }

    /**
     * An anonymous request, refused by shape: the API answers {@code 401 {"error":"not signed in"}},
     * the browser is sent to the form with its return path, never JSON.
     */
    private static void rejectAnonymous(HttpExchange ex, String path) {
        if (path.equals("/head") || path.startsWith("/api/")) {
            sendError(ex, 401, "not signed in");
            return;
        }
        String next = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        if (query != null && !query.isEmpty()) {
            next = next + "?" + query;
        }
        ex.getResponseHeaders().set("Location", "/login?next=" + urlEncode(next));
        try {
            ex.sendResponseHeaders(302, -1);
        } catch (IOException e) {
            log.debug("could not send the login redirect", e);
        }
    }

    /** {@code POST /api/login}: verify in constant time, then set the session cookie. */
    private void login(HttpExchange ex, HubApi api) throws IOException {
        LoginRequest request;
        try {
            request = Json.mapper().readValue(readBody(ex), LoginRequest.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            return;
        }
        String user = request.user();
        RefdataResponse.UserJson declared = user == null ? null : api.refdata().users().stream()
            .filter(u -> user.equals(u.id())).findFirst().orElse(null);
        boolean ok = declared != null && declared.active() && request.password() != null
            && auth.verifyCredentials(user, request.password());
        if (!ok) {
            // A-9: a fixed delay per failure, no lockout. The body is uniform, so it reveals neither
            // whether the id exists, nor that it is inactive, nor that the password is wrong; the
            // user is named only in the host log.
            sleepBeforeRefusal();
            log.warn("failed sign-in for '{}'", user);
            sendError(ex, 401, "invalid credentials");
            return;
        }
        long now = Instant.now().getEpochSecond();
        ex.getResponseHeaders().add("Set-Cookie", auth.session().cookie(declared.id(), now));
        write(ex, 200, Map.of("user", declared.id(), "name", declared.name()));
    }

    /** {@code POST /api/logout}: clear the cookie. There is no session table to touch. */
    private static void logout(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Set-Cookie", Session.clearCookie());
        write(ex, 200, Map.of("ok", true));
    }

    /**
     * {@code GET /api/whoami}: the shell's first call (V2-SPEC.md §9.1, §9.5). With a session it is
     * the signed-in principal and {@code signedIn: true}; with sign-in off it is the default acting
     * user and {@code signedIn: false}, so the shell can still name someone. It is a read and grants
     * nothing. With {@code --auth} the gate has refused an anonymous caller before this, so the
     * resolve below only ever returns null defensively.
     */
    private void whoami(HttpExchange ex, HubApi api) throws IOException {
        if (auth == null) {
            RefdataResponse.UserJson defaultUser = defaultActingUser(api);
            if (defaultUser == null) {
                // users.yaml is non-empty by spec (§12), so this is a guard, not a path.
                sendError(ex, 401, "no declared user");
                return;
            }
            write(ex, 200, Map.of("user", defaultUser.id(), "name", defaultUser.name(),
                "signedIn", false));
            return;
        }
        Session.Principal principal = auth.resolve(ex.getRequestHeaders().getFirst("Cookie"),
            Instant.now().getEpochSecond());
        if (principal == null) {
            // `gated` already rejected an anonymous caller; this only keeps a null `auth` impossible.
            sendError(ex, 401, "not signed in");
            return;
        }
        write(ex, 200, Map.of("user", principal.user(), "name", nameOf(api, principal.user()),
            "signedIn", true));
    }

    /**
     * The default acting user when sign-in is off (V2-SPEC.md §9.5): the first declared {@code
     * active} user in {@code users.yaml}, else the first declared user, else null. The inactive
     * skip matters because a config may lead with a person who has no hash yet.
     */
    private static RefdataResponse.UserJson defaultActingUser(HubApi api) {
        List<RefdataResponse.UserJson> users = api.refdata().users();
        if (users.isEmpty()) {
            return null;
        }
        for (RefdataResponse.UserJson user : users) {
            if (user.active()) {
                return user;
            }
        }
        return users.get(0);
    }

    /** The declared name for a signed-in id; an id the config no longer declares still answers. */
    private static String nameOf(HubApi api, String user) {
        for (RefdataResponse.UserJson u : api.refdata().users()) {
            if (u.id().equals(user)) {
                return u.name();
            }
        }
        return user;
    }

    private static void sleepBeforeRefusal() {
        try {
            Thread.sleep(SIGN_IN_REFUSAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- attribution: the session signs the write (V2-SPEC.md §9.5) --------------------------

    /** The session user parked by {@link #gated}, or null when sign-in is off (A-7). */
    private static String sessionUser(HttpExchange ex) {
        Object user = ex.getAttribute(SESSION_USER);
        return user == null ? null : (String) user;
    }

    /** The id a draft claims that is not the session's, or null when no draft names a foreign user. */
    private static String foreignUser(DecisionRequest request, String session) {
        for (DecisionDraft draft : request.decisions()) {
            String named = draft.user();
            if (named != null && !named.isBlank() && !named.equals(session)) {
                return named;
            }
        }
        return null;
    }

    /** The same drafts, every one re-signed by the session's user. */
    private static List<DecisionDraft> stampDecisions(List<DecisionDraft> decisions, String session) {
        List<DecisionDraft> stamped = new ArrayList<>(decisions.size());
        for (DecisionDraft draft : decisions) {
            stamped.add(draft.withUser(session));
        }
        return stamped;
    }

    // ---- routing ----------------------------------------------------------------------------

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    /** A gated context (the SSE stream and the job proxy do their own method dispatch). */
    private void context(HttpServer server, String path, Handler handler) {
        server.createContext(path, ex -> {
            if (!gated(ex)) {
                ex.close();
                return;
            }
            try {
                handler.handle(ex);
            } catch (Exception e) {
                log.error("500 {} {}: unhandled failure", ex.getRequestMethod(), path, e);
                try {
                    sendError(ex, 500, "internal error: " + e.getMessage());
                } finally {
                    ex.close();
                }
            }
        });
    }

    private void route(HttpServer server, String path, String method, Handler handler) {
        route(server, path, Map.of(method, handler));
    }

    /** A route that skips the gate: the sign-in endpoints (V2-SPEC.md §9.5). */
    private void routeOpen(HttpServer server, String path, String method, Handler handler) {
        routeOpen(server, path, Map.of(method, handler));
    }

    private void route(HttpServer server, String path, Map<String, Handler> handlers) {
        server.createContext(path, ex -> {
            try {
                if (!ex.getRequestURI().getPath().equals(path)) {
                    sendError(ex, 404, "not found");
                    return;
                }
                if (!gated(ex)) {
                    return;
                }
                dispatch(ex, path, handlers);
            } catch (Exception e) {
                log.error("500 {} {}: unhandled failure", ex.getRequestMethod(), path, e);
                sendError(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    private void routeOpen(HttpServer server, String path, Map<String, Handler> handlers) {
        server.createContext(path, ex -> {
            try {
                if (!ex.getRequestURI().getPath().equals(path)) {
                    sendError(ex, 404, "not found");
                    return;
                }
                dispatch(ex, path, handlers);
            } catch (Exception e) {
                log.error("500 {} {}: unhandled failure", ex.getRequestMethod(), path, e);
                sendError(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    private static void dispatch(HttpExchange ex, String path, Map<String, Handler> handlers) throws Exception {
        Handler handler = handlers.get(ex.getRequestMethod());
        if (handler == null) {
            ex.getResponseHeaders().set("Allow", String.join(", ", handlers.keySet()));
            sendError(ex, 405, "method not allowed");
            return;
        }
        handler.handle(ex);
    }

    /**
     * Server-Sent Events (V2-PROPOSAL.md §7.4): a snapshot at the current instant, then a delta
     * whenever the index moves. The subscriber is registered before the snapshot is read, so no
     * change can fall between them.
     */
    private static void streamEvents(HttpExchange ex, HubApi api, HubEvents events) {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "method not allowed");
            ex.close();
            return;
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0);
        } catch (IOException e) {
            ex.close();
            return;
        }
        try (OutputStream out = ex.getResponseBody();
             HubEvents.Subscription subscription = events.subscribe()) {
            writeEvent(out, "snapshot", api.head());
            while (events.isOpen()) {
                java.util.Optional<HubEvents.Change> change = subscription.poll(Duration.ofSeconds(15));
                if (change.isPresent()) {
                    writeEvent(out, "delta", change.get());
                } else {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException e) {
            log.debug("SSE client disconnected");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.close();
        }
    }

    private static void writeEvent(OutputStream out, String event, Object data) throws IOException {
        out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + Json.mapper().writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length > 10 * 1024 * 1024) {
            throw new IOException("request body too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A query value that is {@code null} only when the key is absent; present-but-empty is {@code ""}. */
    private static String present(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key)) {
                return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String param(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    // ---- job runner proxy (V2-PROPOSAL.md §5.5) ---------------------------------------------

    /** The Jobs view and the staging inbox, proxied to the loopback runner; the hub stores nothing. */
    private static void jobs(HttpExchange ex, HubApi api, RunnerClient runner) {
        if (runner == null) {
            sendError(ex, 503, "no job runner configured (start the hub with --runner-url)");
            ex.close();
            return;
        }
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String rest = path.length() > "/api/jobs".length() ? path.substring("/api/jobs".length()) : "";
            if (rest.isEmpty() || rest.equals("/")) {
                if (method.equals("GET")) {
                    proxy(ex, runner.get("/jobs"));
                } else {
                    sendError(ex, 405, "method not allowed");
                }
                return;
            }
            if (rest.equals("/adapters") && method.equals("GET")) {
                proxy(ex, runner.get("/adapters"));
                return;
            }
            if (rest.equals("/staging")) {
                if (method.equals("GET")) {
                    proxyStaging(ex, api, runner);
                } else if (method.equals("POST")) {
                    String name = param(ex.getRequestURI().getRawQuery(), "name");
                    String target = "/staging" + (name == null ? "" : "?name=" + urlEncode(name));
                    proxy(ex, runner.postUpload(target, () -> ex.getRequestBody()));
                } else {
                    sendError(ex, 405, "method not allowed");
                }
                return;
            }
            if (rest.equals("/staging/clear") && method.equals("POST")) {
                String name = param(ex.getRequestURI().getRawQuery(), "name");
                String target = "/staging/clear" + (name == null ? "" : "?name=" + urlEncode(name));
                proxy(ex, runner.postJson(target, new byte[0]));
                return;
            }
            if (rest.equals("/runs") && method.equals("GET")) {
                proxy(ex, runner.get("/jobs/runs"));
                return;
            }
            if (rest.startsWith("/runs/")) {
                String tail = rest.substring("/runs/".length());
                String id = tail.contains("/") ? tail.substring(0, tail.indexOf('/')) : tail;
                if (tail.equals(id) && method.equals("GET")) {
                    proxy(ex, runner.get("/jobs/runs/" + id));
                    return;
                }
                if (tail.equals(id + "/events") && method.equals("GET")) {
                    proxyStream(ex, runner, "/jobs/runs/" + id + "/events");
                    return;
                }
                if (tail.equals(id + "/cancel") && method.equals("POST")) {
                    proxy(ex, runner.postJson("/jobs/runs/" + id + "/cancel", new byte[0]));
                    return;
                }
            }
            if (rest.endsWith("/runs") && method.equals("POST")) {
                String job = rest.substring(1, rest.length() - "/runs".length());
                if (job.isEmpty() || job.contains("/")) {
                    sendError(ex, 404, "not found");
                    return;
                }
                byte[] body = readBody(ex).getBytes(StandardCharsets.UTF_8);
                String query = ex.getRequestURI().getRawQuery();
                String target = "/jobs/" + job + "/runs" + (query == null || query.isBlank() ? "" : "?" + query);
                proxy(ex, runner.postJson(target, body));
                return;
            }
            sendError(ex, 404, "not found");
        } catch (Exception e) {
            log.error("job proxy failed", e);
            sendError(ex, 502, "job runner unreachable: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private static void proxy(HttpExchange ex, RunnerClient.Resp resp) throws IOException {
        ex.getResponseHeaders().set("Content-Type", resp.contentType());
        ex.sendResponseHeaders(resp.status(), resp.body().length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(resp.body());
        }
    }

    private static void proxyStream(HttpExchange ex, RunnerClient runner, String path)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> upstream = runner.stream(path);
        ex.getResponseHeaders().set("Content-Type",
            upstream.headers().firstValue("Content-Type").orElse("text/event-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(upstream.statusCode(), 0);
        try (InputStream in = upstream.body();
             OutputStream out = ex.getResponseBody()) {
            in.transferTo(out);
        }
    }

    /** The runner's staging list, enriched with the tick derived from the log (facts' evidence ids). */
    private static void proxyStaging(HttpExchange ex, HubApi api, RunnerClient runner) throws Exception {
        RunnerClient.Resp resp = runner.get("/staging");
        if (resp.status() != 200) {
            proxy(ex, resp);
            return;
        }
        Set<String> known = api.ingestedEvidenceIds();
        JsonNode root = Json.mapper().readTree(resp.body());
        if (root instanceof ArrayNode array) {
            for (JsonNode node : array) {
                if (node instanceof ObjectNode object) {
                    String evidenceId = object.path("evidenceId").asText(null);
                    object.put("ingested", evidenceId != null && known.contains(evidenceId));
                }
            }
        }
        byte[] bytes = Json.mapper().writeValueAsBytes(root);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void writeText(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) {
        try {
            write(ex, status, new ErrorResponse(message == null ? "" : message));
        } catch (IOException | RuntimeException e) {
            log.debug("could not send {} response", status, e);
        }
    }
}
