package trex.egress.hledger;

import trex.category.Merchant;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.TypeHint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A trex journal as an hledger file. SPEC §5.9. Pure: rows in, text out.
 * <p>
 * This projects a <b>different unit</b> from the Firefly egress, and the difference is the whole
 * point. Firefly wants resolved units, so unresolved rows are withheld. hledger wants
 * <b>completeness</b>: the bank's running balance accounts for every movement, so a file that
 * omits the HELD rows cannot assert against it. Nothing is withheld here — an unresolved movement
 * posts its known side and balances against a suspense account, and
 * {@code hledger balance assets:unresolved} becomes a live list of what still needs deciding.
 * <p>
 * <b>Balance assertions are the reason this egress is worth having.</b> trex carries the bank's own
 * running balance on every line, which SPEC §0.1 reserves for provenance and <em>reconciliation</em>;
 * asserting it is exactly that. hledger then verifies every account against what the bank said and
 * fails at the transaction where it first stops being true.
 */
public final class Ledger {

    /** A row with the category the gateway derived for it. */
    public record Row(CanonicalEvent line, String category) {}

    /**
     * What an account held before trex saw anything.
     *
     * @param ref   the trex account
     * @param cents the derived opening balance
     * @param date  the day before the account's first transaction
     * @param gap   how far the backward derivation disagrees, in cents; see {@link #openings}
     */
    public record Opening(String ref, long cents, LocalDate date, String currency, long gap) {}

    private final Accounts accounts;
    private final boolean assertBalances;
    private List<Opening> lastOpenings = List.of();
    private Map<String, Long> lastUnresolvedDays = Map.of();

    public Ledger(Accounts accounts, boolean assertBalances) {
        this.accounts = accounts;
        this.assertBalances = assertBalances;
    }

    /** The openings derived by the last {@link #render} call, for reporting. */
    public List<Opening> openings() {
        return lastOpenings;
    }

    /**
     * Days per account whose order could not be read, and which therefore carry no assertion.
     * Each one is a day the bank counted a line trex does not have.
     */
    public Map<String, Long> unresolvedDays() {
        return lastUnresolvedDays;
    }

    public String render(List<Row> rows, long asOfN, String rulesRevision) {
        // A TRANSFER line replaces its legs, exactly as in the Firefly egress — posting both would
        // count every internal movement twice. The legs are still read, for their balances.
        Set<String> legIds = rows.stream()
            .map(Row::line)
            .filter(l -> l.legIds() != null)
            .flatMap(l -> l.legIds().stream())
            .collect(Collectors.toSet());

        Map<String, Map<LocalDate, Day>> days = days(rows);
        Map<String, Long> clock = clockOrder(days, rows, legIds);
        List<Row> emitted = rows.stream()
            .filter(r -> !legIds.contains(r.line().externalId()))
            .sorted(Comparator.comparing((Row r) -> r.line().date())
                .thenComparingLong(r -> clock.getOrDefault(r.line().externalId(), r.line().n()))
                .thenComparingLong(r -> r.line().n()))
            .toList();

        Map<String, Long> assertions = closingBalances(days, emitted);
        lastOpenings = openings(days, rows);
        lastUnresolvedDays = days.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
            e -> e.getValue().values().stream().filter(d -> d.closing() == null).count()));

        StringBuilder out = new StringBuilder();
        header(out, emitted.size(), asOfN, rulesRevision);
        declarations(out, emitted, lastOpenings);

        // Openings are merged into the date-ordered stream rather than parked at the top, so the
        // file stays in date order and an account that starts later opens where it starts.
        int next = 0;
        for (Row row : emitted) {
            while (next < lastOpenings.size()
                && !lastOpenings.get(next).date().isAfter(row.line().date())) {
                opening(out, lastOpenings.get(next++));
            }
            transaction(out, row, assertions);
        }
        while (next < lastOpenings.size()) {
            opening(out, lastOpenings.get(next++));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- openings

    /**
     * What each account held before trex's first line for it.
     * <p>
     * Without this every assertion fails at the first transaction, because the bank's running
     * balance counts a history the journal does not have. SPEC §0.1 permits exactly this use of
     * {@code balance}: provenance and <em>reconciliation</em>.
     * <p>
     * Derived <b>forward</b>, from the close of the account's first day minus everything that
     * moved on that day — the opposite of the Firefly egress, on purpose. Firefly anchors backward
     * so that <em>today's</em> balance is right even when history is missing. hledger's job here is
     * the opposite: to <em>find</em> the missing history. Anchoring forward makes the early
     * assertions hold and the first one after a gap fail, which names the gap. Anchoring backward
     * would instead fail every assertion before it, which names nothing.
     * <p>
     * The first <em>day</em> rather than the first line, because a statement does not record the
     * order of transactions within a day: with two lines sharing the earliest date, the implied
     * opening differs by one of their amounts depending on which you pick. Subtracting the whole
     * day removes the question — addition does not care about order.
     * <p>
     * {@code gap} is the backward derivation's disagreement, which is the value of the
     * transactions the bank's balance knows about and the journal does not. Non-zero means hledger
     * is about to find a real hole, and where.
     */
    private List<Opening> openings(Map<String, Map<LocalDate, Day>> days, List<Row> rows) {
        Map<String, List<CanonicalEvent>> byAccount = new LinkedHashMap<>();
        for (Row r : rows) {
            CanonicalEvent line = r.line();
            if (line.typeHint() != TypeHint.TRANSFER) {
                byAccount.computeIfAbsent(line.accountRef(), _ -> new ArrayList<>()).add(line);
            }
        }
        List<Opening> out = new ArrayList<>();
        days.forEach((ref, byDate) -> {
            List<CanonicalEvent> lines = byAccount.get(ref);
            List<LocalDate> resolved = byDate.entrySet().stream()
                .filter(e -> e.getValue().closing() != null).map(Map.Entry::getKey).toList();
            if (resolved.isEmpty()) {
                return;                      // nothing anchors this account; assert nothing on it
            }
            long forward = openingFrom(byDate, lines, resolved.getFirst());
            long backward = openingFrom(byDate, lines, resolved.getLast());
            LocalDate firstDay = byDate.keySet().iterator().next();
            out.add(new Opening(ref, forward, firstDay.minusDays(1),
                lines.getFirst().currency(), backward - forward));
        });
        out.sort(Comparator.comparing(Opening::date).thenComparing(Opening::ref));
        return List.copyOf(out);
    }

    /** The opening implied by one anchor day: its close, less everything that moved up to it. */
    private static long openingFrom(Map<LocalDate, Day> byDate, List<CanonicalEvent> lines, LocalDate anchor) {
        long moved = lines.stream().filter(l -> !l.date().isAfter(anchor))
            .mapToLong(CanonicalEvent::amount).sum();
        return byDate.get(anchor).closing() - moved;
    }

    private void opening(StringBuilder out, Opening o) {
        out.append(o.date()).append(" * Opening balance — ").append(o.ref()).append('\n');
        out.append("    ; derived from the bank's running balance, not ingested (SPEC §0.1)\n");
        if (o.gap() != 0) {
            out.append("    ; WARNING: the backward derivation disagrees by ")
                .append(money(o.gap(), o.currency()))
                .append(" — the journal is missing history for this account,\n");
            out.append("    ; and the first assertion below that fails is where.\n");
        }
        posting(out, accounts.of(o.ref()), o.cents(), o.currency(), null);
        out.append("    ").append(accounts.equity()).append('\n');
        out.append('\n');
    }

    // ---------------------------------------------------------------- chronology

    /**
     * The order the bank's clock ran in, per account, read out of the balance column.
     * <p>
     * A journal line's {@code n} is ingest order, and ingest order is CSV row order — which is the
     * bank's choice, not trex's. BankWest exports newest first, ING oldest first, and nothing in
     * the journal records which. Taking the highest {@code n} on a day as that day's closing
     * balance is therefore wrong for half the accounts, and wrong invisibly: it picks a real
     * balance from a real row, just not the last one.
     * <p>
     * The balance column settles it without any per-bank configuration. Read two adjacent lines:
     * if the later one's balance is the earlier one's plus the later one's amount, {@code n} runs
     * with the clock; if it is the earlier one's minus the <em>earlier</em> one's amount, it runs
     * against it. Both can match by coincidence on a single pair, so it is a majority over every
     * pair, and an account with one line has no order to get wrong.
     *
     * @return the account's lines in the order they actually happened
     */
    private static List<CanonicalEvent> chronological(List<CanonicalEvent> lines) {
        List<CanonicalEvent> byN = lines.stream()
            .sorted(Comparator.comparingLong(CanonicalEvent::n)).toList();
        int withClock = 0;
        int against = 0;
        for (int i = 0; i + 1 < byN.size(); i++) {
            CanonicalEvent a = byN.get(i);
            CanonicalEvent b = byN.get(i + 1);
            if (b.balance() == a.balance() + b.amount()) {
                withClock++;
            }
            if (b.balance() == a.balance() - a.amount()) {
                against++;
            }
        }
        return against > withClock ? byN.reversed() : byN;
    }

    // ---------------------------------------------------------------- chronology

    /**
     * One account's transactions on one day.
     *
     * @param order   best-effort chronological order; {@code n} order when the chain was ambiguous
     * @param closing the balance the account closed the day on, or null when it is not knowable
     */
    private record Day(List<CanonicalEvent> order, Long closing) {}

    /**
     * Put one account-day in the order it happened, by chaining the balance column.
     * <p>
     * A journal line's {@code n} is ingest order, and ingest order is CSV row order, which is the
     * bank's choice and not trex's. BankWest exports newest first. ING exports newest first
     * <em>and</em> splits deposits and withdrawals into separate files, so one account's lines
     * arrive as two interleaved runs. Neither ordering is recorded anywhere, and taking the
     * highest {@code n} on a day as that day's close is therefore wrong for most accounts — wrong
     * invisibly, because it picks a real balance off a real row, just not the last one.
     * <p>
     * The balance column settles it with no per-bank configuration, because it is a running
     * balance for the whole account rather than for the file: a row's balance minus its own amount
     * is the balance of whatever came immediately before it. So exactly one row on the day is a
     * tail — its balance is no other row's "before" — and walking back from it links the rest.
     * <p>
     * Two different things can go wrong, and they have different costs. If <b>more than one row
     * is a tail</b>, the day's lines fall into disconnected runs: trex is missing a line the bank
     * counted, the closing balance is not knowable, and nothing is asserted — a guess there would
     * assert a figure wrong by the amount of the line we do not have. If the tail is unique but
     * the walk back from it is <b>ambiguous in the middle</b> — two rows on the day happen to
     * share a balance — the close is still known and still asserted; only the printed order of
     * the day falls back to {@code n}. Intra-day order was never knowable from a statement
     * anyway, so that costs presentation rather than correctness.
     */
    private static Day dayOrder(List<CanonicalEvent> day) {
        List<CanonicalEvent> byN = day.stream()
            .sorted(Comparator.comparingLong(CanonicalEvent::n)).toList();
        if (byN.size() == 1) {
            return new Day(byN, byN.getFirst().balance());
        }
        Set<Long> before = byN.stream().map(l -> l.balance() - l.amount()).collect(Collectors.toSet());
        List<CanonicalEvent> tails = byN.stream().filter(l -> !before.contains(l.balance())).toList();
        if (tails.size() != 1) {
            return new Day(byN, null);
        }
        // The tail is the close, and that is all an assertion needs. The chain below only decides
        // the order the day is printed in, so an ambiguity there costs presentation, not
        // correctness — the day still reconciles.
        Long closing = tails.getFirst().balance();
        Map<Long, List<CanonicalEvent>> byBalance = byN.stream()
            .collect(Collectors.groupingBy(CanonicalEvent::balance));
        ArrayDeque<CanonicalEvent> chain = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        CanonicalEvent cursor = tails.getFirst();
        while (cursor != null) {
            if (!seen.add(cursor.externalId())) {
                return new Day(byN, closing);                       // a cycle: amounts collided
            }
            chain.addFirst(cursor);
            List<CanonicalEvent> previous =
                byBalance.getOrDefault(cursor.balance() - cursor.amount(), List.of()).stream()
                    .filter(l -> !seen.contains(l.externalId())).toList();
            if (previous.size() > 1) {
                return new Day(byN, closing);                       // two rows could be the previous
            }
            cursor = previous.isEmpty() ? null : previous.getFirst();
        }
        return chain.size() == byN.size()
            ? new Day(List.copyOf(chain), closing)
            : new Day(byN, closing);
    }

    /** Every account-day, ordered. TRANSFER lines are excluded: they carry no balance. */
    private static Map<String, Map<LocalDate, Day>> days(List<Row> rows) {
        Map<String, Map<LocalDate, List<CanonicalEvent>>> raw = new LinkedHashMap<>();
        for (Row r : rows) {
            CanonicalEvent line = r.line();
            if (line.typeHint() == TypeHint.TRANSFER) {
                continue;
            }
            raw.computeIfAbsent(line.accountRef(), _ -> new LinkedHashMap<>())
                .computeIfAbsent(line.date(), _ -> new ArrayList<>()).add(line);
        }
        Map<String, Map<LocalDate, Day>> out = new LinkedHashMap<>();
        raw.forEach((ref, byDate) -> {
            Map<LocalDate, Day> ordered = new java.util.TreeMap<>();
            byDate.forEach((date, lines) -> ordered.put(date, dayOrder(lines)));
            out.put(ref, ordered);
        });
        return out;
    }

    /**
     * A sort key per emitted row that puts a day's transactions in the order they happened.
     * <p>
     * Sorting by {@code n} alone lists a newest-first bank's day backwards. It would still
     * reconcile — the day's closing assertion lands on whichever posting is last in the file — but
     * {@code hledger reg} would print a running balance that steps through the day in reverse and
     * matches no statement anyone can hold up beside it. A TRANSFER line takes its leg's place in
     * the order, since that is where the movement actually sat.
     */
    private static Map<String, Long> clockOrder(Map<String, Map<LocalDate, Day>> days,
                                                List<Row> rows, Set<String> legIds) {
        Map<String, Long> out = new HashMap<>();
        days.values().forEach(byDate -> byDate.values().forEach(day -> {
            for (int i = 0; i < day.order().size(); i++) {
                out.put(day.order().get(i).externalId(), (long) i);
            }
        }));
        rows.stream().map(Row::line)
            .filter(l -> l.typeHint() == TypeHint.TRANSFER && l.legIds() != null)
            .forEach(l -> l.legIds().stream()
                .filter(legIds::contains)
                .map(out::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(rank -> out.put(l.externalId(), rank)));
        return out;
    }

    // ---------------------------------------------------------------- assertions

    /**
     * Each account's closing balance on each day, attached to the last posting the file makes to
     * that account that day.
     * <p>
     * Only the day's <em>closing</em> balance is asserted, never a mid-day one, and only where
     * {@link #dayOrder} could read the order. Two things have to line up: the balance comes from
     * the day's chronologically last line, and the assertion has to sit on the last posting to
     * that account in the <em>file</em>, because that is where hledger compares it against the
     * running total. They are usually the same row and need not be — a TRANSFER line is written
     * where its legs were matched — so they are found separately.
     *
     * @return key {@code externalId|accountRef} to balance in cents
     */
    private Map<String, Long> closingBalances(Map<String, Map<LocalDate, Day>> days, List<Row> emitted) {
        record Key(String account, LocalDate date) {}
        // Emitted is in file order, so the last write for a key is the last posting of that day.
        Map<Key, String> anchor = new HashMap<>();
        for (Row r : emitted) {
            CanonicalEvent line = r.line();
            anchor.put(new Key(line.accountRef(), line.date()), line.externalId());
            if (line.typeHint() == TypeHint.TRANSFER && line.toAccountRef() != null) {
                anchor.put(new Key(line.toAccountRef(), line.date()), line.externalId());
            }
        }
        Map<String, Long> out = new HashMap<>();
        days.forEach((ref, byDate) -> byDate.forEach((date, day) -> {
            String id = anchor.get(new Key(ref, date));
            if (id != null && day.closing() != null) {
                out.put(id + "|" + ref, day.closing());
            }
        }));
        return out;
    }

    // ---------------------------------------------------------------- rendering

    private void header(StringBuilder out, int count, long asOfN, String rulesRevision) {
        out.append("; hledger journal generated by trex-egress-hledger (SPEC §5.9)\n");
        out.append("; DO NOT EDIT — this file is rewritten in full on every run.\n");
        out.append("; Corrections belong in trex: a pin or a rule, not a change here.\n;\n");
        out.append("; journal n=").append(asOfN)
            .append("  rules=").append(rulesRevision == null ? "none" : rulesRevision)
            .append("  transactions=").append(count).append('\n');
        if (assertBalances) {
            out.append("; balance assertions are the bank's own running balance, asserted on the\n");
            out.append("; last transaction per account per day (SPEC §0.1: reconciliation use).\n");
        }
        out.append('\n');
    }

    /** Declaring accounts lets {@code hledger check accounts} catch a typo in a name. */
    private void declarations(StringBuilder out, List<Row> rows, List<Opening> openings) {
        TreeSet<String> names = rows.stream()
            .flatMap(r -> postingAccounts(r).stream())
            .collect(Collectors.toCollection(TreeSet::new));
        openings.forEach(o -> names.add(accounts.of(o.ref())));
        if (!openings.isEmpty()) {
            names.add(accounts.equity());
        }
        names.forEach(n -> out.append("account ").append(n).append('\n'));
        out.append('\n');
    }

    private List<String> postingAccounts(Row row) {
        CanonicalEvent line = row.line();
        if (line.typeHint() == TypeHint.TRANSFER) {
            return List.of(accounts.of(line.accountRef()), accounts.of(line.toAccountRef()));
        }
        return List.of(accounts.of(line.accountRef()), contra(row));
    }

    private void transaction(StringBuilder out, Row row, Map<String, Long> assertions) {
        CanonicalEvent line = row.line();
        out.append(line.date()).append(' ').append(status(line)).append(' ')
            .append(clean(description(line))).append('\n');
        out.append("    ; id: ").append(line.externalId()).append('\n');
        out.append("    ; category: ").append(row.category()).append('\n');
        if (line.state() == EventState.HELD || line.state() == EventState.REVIEW) {
            out.append("    ; unresolved: ").append(line.state()).append('\n');
        }

        if (line.typeHint() == TypeHint.TRANSFER) {
            long amount = Math.abs(line.amount());
            posting(out, accounts.of(line.accountRef()), -amount, line.currency(),
                assertions.get(line.externalId() + "|" + line.accountRef()));
            posting(out, accounts.of(line.toAccountRef()), amount, line.currency(),
                assertions.get(line.externalId() + "|" + line.toAccountRef()));
        } else {
            posting(out, accounts.of(line.accountRef()), line.amount(), line.currency(),
                assertions.get(line.externalId() + "|" + line.accountRef()));
            // The contra is elided: hledger infers it, and an inferred amount can never disagree
            // with the one above it.
            out.append("    ").append(contra(row)).append('\n');
        }
        out.append('\n');
    }

    /**
     * Where the other side of the movement goes. The category is part of the account tree rather
     * than a tag, so {@code hledger balance expenses:groceries} works with no extra machinery —
     * which is the whole reason to prefer a plain-text target for reporting.
     * <p>
     * The top level follows the <b>category</b>, not the sign. A Bunnings refund is money coming
     * in, but it is not income: it belongs in the same expense account as the purchase it reverses,
     * as a negative amount, so that {@code hledger balance expenses:home-improvement} nets to what
     * was actually spent. Deciding by sign instead files every refund under {@code income:} and
     * overstates both sides of the report. Which categories are income is config, because only the
     * person who wrote the category list knows.
     */
    private String contra(Row row) {
        CanonicalEvent line = row.line();
        if (line.state() == EventState.HELD || line.state() == EventState.REVIEW) {
            return accounts.unresolved();
        }
        String merchant = Accounts.slug(Merchant.stem(line.rawDescription()));
        String category = Accounts.slug(row.category());
        return accounts.top(row.category(), line.amount()) + ":" + category + ":" + merchant;
    }

    /** {@code *} is cleared, {@code !} is pending — hledger's own vocabulary for undecided. */
    private static String status(CanonicalEvent line) {
        return line.state() == EventState.HELD || line.state() == EventState.REVIEW ? "!" : "*";
    }

    private static String description(CanonicalEvent line) {
        String d = line.description();
        return d == null || d.isBlank() ? line.rawDescription() : d;
    }

    private void posting(StringBuilder out, String account, long cents, String currency, Long assertion) {
        out.append("    ").append(pad(account)).append("  ").append(money(cents, currency));
        if (assertBalances && assertion != null) {
            out.append(" = ").append(money(assertion, currency));
        }
        out.append('\n');
    }

    private static String pad(String account) {
        return account.length() >= 48 ? account : account + " ".repeat(48 - account.length());
    }

    /** Cents to an exact decimal. Never a float; the assertion has to match to the cent. */
    static String money(long cents, String currency) {
        BigDecimal amount = BigDecimal.valueOf(cents, 2);
        String symbol = "AUD".equals(currency) ? "$" : currency + " ";
        return amount.signum() < 0
            ? "-" + symbol + amount.negate().toPlainString()
            : symbol + amount.toPlainString();
    }

    /** A description must stay on one line and must not open a comment. */
    static String clean(String text) {
        if (text == null) {
            return "(no description)";
        }
        String s = text.replaceAll("\\s+", " ").replace(';', ',').strip();
        return s.isBlank() ? "(no description)" : s;
    }
}
