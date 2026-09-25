package trex.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import trex.core.BalanceSource;
import trex.core.Candidate;
import trex.core.Provenance;
import trex.journal.Json;
import trex.core.account.Account;
import trex.core.account.AccountRegistry;
import trex.ws.Web.HttpError;
import trex.ws.ledger.SequencerClient;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/accounts} and {@code POST /api/cash}. SPEC §5.7.
 * <p>
 * The accounts endpoint exists so the registry has <b>one</b> owner that consumers read. Copying
 * {@code balanceSource} into {@code hledger.yaml} and {@code firefly.yaml} would give two files
 * that can disagree about the same account, which is the argument that produced this service in
 * the first place (DECISIONS V1).
 * <p>
 * {@code /api/cash} is the only way a line enters the journal without a bank behind it, so it is
 * the only place that can put uncorroborated data in — hence the checks here rather than anywhere
 * downstream. The sequencer remains the authority (§3.5); these fail fast and never disagree
 * with it.
 */
final class CashRoutes {

    private final AccountRegistry registry;
    private final SequencerClient sequencer;

    CashRoutes(AccountRegistry registry, SequencerClient sequencer) {
        this.registry = registry;
        this.sequencer = sequencer;
    }

    void handle(HttpExchange ex, String path) throws Exception {
        switch (path) {
            case "/api/accounts" -> {
                Web.requireMethod(ex, "GET");
                Web.json(ex, 200, accounts());
            }
            case "/api/cash" -> {
                Web.requireMethod(ex, "POST");
                cash(ex);
            }
            default -> throw new HttpError(404, "not found");
        }
    }

    private List<Map<String, Object>> accounts() {
        if (registry == null) {
            return List.of();
        }
        return registry.all().stream()
            .map(a -> Map.<String, Object>of(
                "ref", a.ref(),
                "currency", a.currency(),
                "balanceSource", a.balanceSource().name().toLowerCase(java.util.Locale.ROOT)))
            .toList();
    }

    /**
     * Either a purchase — {@code amount} and {@code description} — or an attestation, which
     * carries {@code attestedBalance} and neither of those. Exactly one shape per call, because a
     * declaration is its own event and never a rider on a purchase: they happen on different
     * schedules, and one line per call is what the append-only log already is.
     */
    private void cash(HttpExchange ex) throws Exception {
        if (registry == null) {
            throw new HttpError(503, "no --config directory: this service cannot see accounts.yaml");
        }
        JsonNode body = body(ex);

        String ref = text(body, "ref");
        if (ref == null || ref.isBlank()) {
            throw new HttpError(422, "ref is required: it is this entry's identity (§5.7)");
        }
        String accountRef = text(body, "accountRef");
        Account account = registry.find(accountRef).orElse(null);
        if (account == null) {
            throw new HttpError(422, "unknown accountRef: " + accountRef);
        }
        // The refusal that matters. A hand-entered line on a statement account would corrupt a
        // chain the bank is the authority for, and nothing downstream could tell afterwards.
        if (account.balanceSource() != BalanceSource.DECLARED) {
            throw new HttpError(422, accountRef + " is balanceSource: statement — a hand-entered "
                + "line would corrupt a chain the bank owns. Cash accounts are 'declared' (§6)");
        }
        LocalDate date = date(body);

        boolean attesting = body.hasNonNull("attestedBalance");
        boolean spending = body.hasNonNull("amount");
        if (attesting == spending) {
            throw new HttpError(422, "exactly one of 'amount' (a purchase) or 'attestedBalance' "
                + "(a statement of what is left) — an attestation is its own event");
        }

        Candidate candidate;
        if (attesting) {
            if (body.hasNonNull("description")) {
                throw new HttpError(422, "an attestation records no movement, so it takes no description");
            }
            long attested = body.get("attestedBalance").asLong();
            candidate = new Candidate(ref, accountRef, date, 0, "Cash attestation",
                attested, ref, null, null, "manual", Provenance.AUTHORED);
        } else {
            long amount = body.get("amount").asLong();
            if (amount == 0) {
                throw new HttpError(422, "a purchase moves a non-zero amount");
            }
            String description = text(body, "description");
            if (description == null || description.isBlank()) {
                throw new HttpError(422, "description is required: nothing else will identify "
                    + "this line later, because no bank wrote it");
            }
            candidate = new Candidate(ref, accountRef, date, amount, description,
                0, ref, null, null, "manual", Provenance.AUTHORED);
        }
        // The sequencer's own batch shape; it validates and decides, this only carries (§3.5).
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("allOrNone", true);
        batch.put("batch", List.of(candidate));
        SequencerClient.Reply reply =
            sequencer.postCandidates(Json.mapper().writeValueAsBytes(batch));
        Web.relay(ex, reply);
    }

    private static LocalDate date(JsonNode body) throws HttpError {
        String raw = text(body, "date");
        if (raw == null) {
            throw new HttpError(422, "date is required");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new HttpError(422, "date must be yyyy-mm-dd, not '" + raw + "'");
        }
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    private static JsonNode body(HttpExchange ex) throws IOException, HttpError {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            throw new HttpError(400, "empty body");
        }
        try {
            return Json.mapper().readTree(bytes);
        } catch (IOException e) {
            throw new HttpError(400, "invalid JSON");
        }
    }

}
