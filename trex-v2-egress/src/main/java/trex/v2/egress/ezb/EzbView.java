package trex.v2.egress.ezb;

import trex.v2.egress.Progress;
import trex.v2.egress.hub.HubClient;
import trex.v2.egress.hub.HubClient.HubUnit;
import trex.v2.egress.hub.HubClient.OpeningState;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One ezbookkeeping view pass (V2-SPEC.md §11.2): plan / apply / verify.
 *
 * <p>A <b>disposable projection, not a converging mirror</b>. Input is the same {@code /api/units}
 * feed Firefly uses; there is no external id, no ownership tag, no fingerprint, no projection state.
 * Every apply clears the transactions and re-imports the whole view — every unit plus every opening —
 * so the next apply heals a half-written one. Accounts and categories are <em>ensured</em> (created
 * when missing, never deleted except a duplicate category leaf), so a saved chart or exploration
 * keeps its ids.
 */
public final class EzbView {

    public enum Mode { PLAN, APPLY, VERIFY }

    public record Outcome(int openings, int expenses, int incomes, int transfers, int accountsCreated,
                          int categoriesCreated, int categoriesRemoved, int cleared, int imported,
                          List<String> mismatches) {}

    /** ezbookkeeping refused something, or a count did not close: the CLI turns this into exit 1. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** The ezbookkeeping primary category that holds one trex category type's children (§11.2). */
    static String primaryName(int categoryType) {
        return switch (categoryType) {
            case EzbRows.CATEGORY_INCOME -> "trex-income";
            case EzbRows.CATEGORY_EXPENSE -> "trex-expense";
            case EzbRows.CATEGORY_TRANSFER -> "trex-transfer";
            default -> throw new IllegalArgumentException("no ezbookkeeping category type " + categoryType);
        };
    }

    /**
     * The primary a needed {@code (type, name)} sits under: an expense category with a mapping in
     * {@link EzbMap#categoryGroups()} uses the mapped group primary, everything else the type's
     * single {@code trex-*} primary. Income and transfer are never grouped (§11.2).
     */
    private String targetPrimary(int categoryType, String name) {
        if (categoryType == EzbRows.CATEGORY_EXPENSE) {
            String group = map.categoryGroups().get(name);
            if (group != null && !group.isBlank()) {
                return group;
            }
        }
        return primaryName(categoryType);
    }

    private static final int IMPORT_BATCH = 500;

    private static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(60);

    private final HubClient hub;
    private final EzbClient ezb;
    private final EzbMap map;
    private final PrintStream out;
    /** Login credentials for the one-call clear; null means clear by id with the API token (§11.2). */
    private final EzbClient.Credentials credentials;

    public EzbView(HubClient hub, EzbClient ezb, EzbMap map, PrintStream out) {
        this(hub, ezb, map, out, null);
    }

    public EzbView(HubClient hub, EzbClient ezb, EzbMap map, PrintStream out,
                   EzbClient.Credentials credentials) {
        this.hub = hub;
        this.ezb = ezb;
        this.map = map;
        this.out = out;
        this.credentials = credentials;
    }

    public Outcome run(Mode mode) throws IOException, InterruptedException {
        List<HubUnit> units = hub.units(0).units();
        List<OpeningState> openingRows = hub.opening();

        // Every account a row could land in: a unit's payer or payee, and every opening's ref.
        TreeSet<String> refs = new TreeSet<>();
        for (HubUnit unit : units) {
            if (unit.accountRef() != null) {
                refs.add(unit.accountRef());
            }
            if (unit.toAccountRef() != null) {
                refs.add(unit.toAccountRef());
            }
        }
        for (OpeningState opening : openingRows) {
            refs.add(opening.accountRef());
        }

        List<String> unmapped = map.missing(new ArrayList<>(refs));
        if (!unmapped.isEmpty()) {
            throw new Refused("ezb.yaml maps no account for: " + String.join(", ", unmapped)
                + ". Add them (name and account category) and rerun; nothing has been written.");
        }

        Map<String, Long> openings = openings(openingRows, units);
        List<EzbRows.Row> rows = EzbRows.of(units, openings, map.zone());
        List<EzbRows.Row> openingList = rows.stream()
            .filter(r -> r.type() == EzbRows.BALANCE_MODIFICATION).toList();
        List<EzbRows.Row> unitList = rows.stream()
            .filter(r -> r.type() != EzbRows.BALANCE_MODIFICATION).toList();
        int expenses = count(unitList, EzbRows.EXPENSE);
        int incomes = count(unitList, EzbRows.INCOME);
        int transfers = count(unitList, EzbRows.TRANSFER);

        return switch (mode) {
            case PLAN -> plan(refs, openingList, unitList, expenses, incomes, transfers);
            case APPLY -> apply(refs, openingList, unitList, expenses, incomes, transfers);
            case VERIFY -> verify(refs, units, openings, rows.size(), openingList, expenses, incomes, transfers);
        };
    }

    /**
     * Every ref's opening (§11.2): a declared wallet with an attestation opens at
     * {@code latestCount − Σ units ≤ latestAt}; any other known account at its backward figure; an
     * account the hub does not report opens at 0.
     */
    private Map<String, Long> openings(List<OpeningState> openingRows, List<HubUnit> units) {
        Map<String, Long> out = new TreeMap<>();
        for (OpeningState opening : openingRows) {
            if (opening.declared() && opening.latestAt() != null) {
                out.put(opening.accountRef(), EzbRows.declaredOpening(opening.accountRef(),
                    opening.latestBalance(), opening.latestAt(), units));
            } else {
                out.put(opening.accountRef(), opening.backwardOpening());
            }
        }
        return out;
    }

    private Outcome plan(TreeSet<String> refs, List<EzbRows.Row> openingList, List<EzbRows.Row> unitList,
                         int expenses, int incomes, int transfers) throws IOException, InterruptedException {
        Map<String, EzbClient.AccountInfo> existing = ezb.accountsByName();
        List<String> accountsToCreate = accountsToCreate(refs, existing);
        Categories categories = ensureCategories(unitList, false);
        out.printf("PLAN: %d opening(s), %d expense(s), %d income(s), %d transfer(s); %d account(s) and "
                + "%d category(ies) to create%n", openingList.size(), expenses, incomes, transfers,
            accountsToCreate.size(), categories.created());
        out.println("  unmapped refs: none");
        accountsToCreate.forEach(name -> out.println("  ACCOUNT  " + name));
        categories.planned().forEach(line -> out.println("  CATEGORY " + line));
        return new Outcome(openingList.size(), expenses, incomes, transfers, 0, 0, 0, 0, 0, List.of());
    }

    private Outcome apply(TreeSet<String> refs, List<EzbRows.Row> openingList, List<EzbRows.Row> unitList,
                          int expenses, int incomes, int transfers)
            throws IOException, InterruptedException {
        // (1) ensure the accounts the view actually uses, so a saved exploration keeps its ids.
        Map<String, String> currency = hub.currencyByAccount();
        Map<String, EzbClient.AccountInfo> accounts = ezb.accountsByName();
        int accountsCreated = accountsToCreate(refs, accounts).size();
        for (String ref : refs) {
            EzbMap.Entry entry = map.get(ref);
            if (accounts.containsKey(entry.name())) {
                continue;
            }
            ezb.addAccount(entry.name(), entry.category(), currency.getOrDefault(ref, "AUD"));
        }
        accounts = ezb.accountsByName();

        // (2) clear — the fast one-call route (data/clear/transactions) when login credentials are
        // configured, else delete each id with the API token, storing no password. Both keep accounts,
        // categories, tags and explorations; both reset every balance to 0, so openings are re-posted.
        List<String> stale = ezb.transactionIds();
        if (credentials != null) {
            ezb.clearAll(credentials);
        } else {
            for (String id : stale) {
                ezb.deleteTransaction(id);
            }
        }

        // (3) ensure the primary/group + child categories, reusing every id that already exists,
        // re-parenting a child to its mapped primary and collapsing duplicate leaves. After the
        // clear: the API allows a move or delete under a live transaction, but doing it here means
        // no transaction ever names a category that is momentarily dangling (§11.2).
        Categories categories = ensureCategories(unitList, true);

        Map<String, String> accountIds = new TreeMap<>();
        accounts.forEach((name, info) -> accountIds.put(name, info.id()));
        List<Map<String, Object>> openingBodies = openingList.stream()
            .map(r -> request(r, categories.childIds(), accountIds)).toList();
        List<Map<String, Object>> unitBodies = unitList.stream()
            .map(r -> request(r, categories.childIds(), accountIds)).toList();

        // (4) import openings first (a type-1 row needs its account otherwise empty), then units in
        // batches, polling the async process after every call. A fresh clientSessionId for EVERY
        // import call: the in-memory duplicate checker keys on the id alone, so a reused one is
        // treated as a repeat submission and every batch after the first is dropped (measured on
        // 2.0.1 — three calls sharing an id landed only the first).
        Progress progress = new Progress(openingBodies.size() + unitBodies.size(), out);
        int imported = 0;
        if (!openingBodies.isEmpty()) {
            ezb.importRows(sessionId(), openingBodies);
            awaitImport();
            imported += tick(progress, openingBodies.size());
        }
        for (int from = 0; from < unitBodies.size(); from += IMPORT_BATCH) {
            List<Map<String, Object>> batch =
                unitBodies.subList(from, Math.min(from + IMPORT_BATCH, unitBodies.size()));
            ezb.importRows(sessionId(), batch);
            awaitImport();
            imported += tick(progress, batch.size());
        }
        progress.done();

        // (5) the poll going idle is not success: the count is the only proof (§11.2).
        int found = ezb.transactionIds().size();
        if (found != imported) {
            throw new Refused("imported " + imported + " row(s) but ezbookkeeping holds " + found
                + "; rerun apply");
        }

        out.printf("APPLY: %d opening(s), %d expense(s), %d income(s), %d transfer(s); %d account(s), "
                + "%d categor(ies) created, %d duplicate categor(ies) removed; %d cleared %s; "
                + "%d imported%n", openingList.size(), expenses, incomes, transfers, accountsCreated,
            categories.created(), categories.removed(), stale.size(),
            credentials != null ? "(one call)" : "(by id)", imported);
        return new Outcome(openingList.size(), expenses, incomes, transfers, accountsCreated,
            categories.created(), categories.removed(), stale.size(), imported, List.of());
    }

    private Outcome verify(TreeSet<String> refs, List<HubUnit> units, Map<String, Long> openings,
                           int rows, List<EzbRows.Row> openingList, int expenses, int incomes,
                           int transfers) throws IOException, InterruptedException {
        Map<String, EzbClient.AccountInfo> accounts = ezb.accountsByName();
        List<String> mismatches = new ArrayList<>();
        for (String ref : refs) {
            // The units view, never /api/opening's latestBalance, which keeps facts dated after asOf
            // (§3). Same sign rule as the declared opening, so the two cannot drift.
            long expected = openings.getOrDefault(ref, 0L) + EzbRows.movement(ref, units, null);
            EzbClient.AccountInfo info = accounts.get(map.get(ref).name());
            if (info == null) {
                mismatches.add(ref + ": expected " + expected + ", found (no account)");
            } else if (info.balance() != expected) {
                mismatches.add(ref + ": expected " + expected + ", found " + info.balance());
            }
        }
        int found = ezb.transactionIds().size();
        if (found != rows) {
            mismatches.add("rows: expected " + rows + ", found " + found);
        }
        out.printf("VERIFY: %d opening(s), %d expense(s), %d income(s), %d transfer(s), %d mismatch(es)%n",
            openingList.size(), expenses, incomes, transfers, mismatches.size());
        mismatches.forEach(m -> out.println("  MISMATCH " + m));
        return new Outcome(openingList.size(), expenses, incomes, transfers, 0, 0, 0, 0, found, mismatches);
    }

    /** The accounts a used, mapped ref names but ezbookkeeping lacks, in sorted-ref order. */
    private List<String> accountsToCreate(TreeSet<String> refs, Map<String, EzbClient.AccountInfo> existing) {
        TreeSet<String> names = new TreeSet<>();
        for (String ref : refs) {
            String name = map.get(ref).name();
            if (!existing.containsKey(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * The primary and child categories the rows need. A primary per needed name and category type;
     * an expense category with a mapping in {@link EzbMap#categoryGroups()} sits under its mapped
     * group primary, everything else under the type's single {@code trex-*} primary. A child named
     * exactly the trex category is reused wherever it already sits: the canonical one (already under
     * the target primary, else the lowest id) is re-parented there and keeps its id; any other leaf
     * with the same {@code (type, name)} is a duplicate and is deleted. With {@code write} false
     * nothing is created, moved or deleted; {@code created}/{@code removed} then count what the plan
     * <em>would</em> do, and {@code planned} lists it.
     */
    private Categories ensureCategories(List<EzbRows.Row> rows, boolean write)
            throws IOException, InterruptedException {
        // (1) the distinct (categoryType, category) pairs the rows need, in deterministic order.
        TreeMap<Integer, TreeSet<String>> byType = new TreeMap<>();
        for (EzbRows.Row row : rows) {
            if (row.category() != null && row.categoryType() != null) {
                byType.computeIfAbsent(row.categoryType(), k -> new TreeSet<>()).add(row.category());
            }
        }
        TreeMap<Integer, TreeSet<String>> primariesByType = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<String>> type : byType.entrySet()) {
            for (String name : type.getValue()) {
                primariesByType.computeIfAbsent(type.getKey(), k -> new TreeSet<>())
                    .add(targetPrimary(type.getKey(), name));
            }
        }

        List<EzbClient.CategoryInfo> existing = new ArrayList<>(ezb.categories());
        Map<String, String> childIds = new TreeMap<>();
        List<String> planned = new ArrayList<>();
        int created = 0;
        int removed = 0;

        // (2) every needed primary, created in write mode; ids keyed by (type|name).
        Map<String, String> primaryIds = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<String>> type : primariesByType.entrySet()) {
            int categoryType = type.getKey();
            for (String name : type.getValue()) {
                String id = find(existing, name, categoryType, "0");
                if (id == null) {
                    created++;
                    planned.add(name + " (primary, type " + categoryType + ")");
                    if (write) {
                        id = ezb.addCategory(name, categoryType, "0");
                        existing.add(new EzbClient.CategoryInfo(id, name, categoryType, "0"));
                    }
                }
                if (id != null) {
                    primaryIds.put(categoryType + "|" + name, id);
                }
            }
        }

        // (3) every needed child, re-parented or created under its target primary; duplicates collapse.
        for (Map.Entry<Integer, TreeSet<String>> type : byType.entrySet()) {
            int categoryType = type.getKey();
            for (String name : type.getValue()) {
                String key = categoryType + "|" + name;
                String primaryName = targetPrimary(categoryType, name);
                String primaryId = primaryIds.get(categoryType + "|" + primaryName);

                // Every existing leaf with this (name, type), wherever it sits, lowest id first.
                List<EzbClient.CategoryInfo> leaves = existing.stream()
                    .filter(c -> c.name().equals(name) && c.categoryType() == categoryType
                        && !"0".equals(c.parentId()))
                    .sorted(Comparator.comparing(EzbClient.CategoryInfo::id))
                    .toList();
                // One is canonical: the one already under the target primary, else the lowest id.
                EzbClient.CategoryInfo canonical = leaves.stream()
                    .filter(c -> c.parentId().equals(primaryId)).findFirst()
                    .orElse(leaves.isEmpty() ? null : leaves.get(0));

                if (canonical == null) {
                    created++;
                    planned.add(name + " (child of " + primaryName + ", type " + categoryType + ")");
                    if (write) {
                        String parent = primaryId == null ? "0" : primaryId;
                        String id = ezb.addCategory(name, categoryType, parent);
                        existing.add(new EzbClient.CategoryInfo(id, name, categoryType, parent));
                        childIds.put(key, id);
                    }
                    continue;
                }

                childIds.put(key, canonical.id());
                if (write && !canonical.parentId().equals(primaryId)) {
                    ezb.moveCategory(canonical.id(), canonical.name(), categoryType, primaryId);
                    existing.set(existing.indexOf(canonical),
                        new EzbClient.CategoryInfo(canonical.id(), canonical.name(), categoryType, primaryId));
                }
                for (EzbClient.CategoryInfo duplicate : leaves) {
                    if (duplicate.id().equals(canonical.id())) {
                        continue;
                    }
                    removed++;
                    if (write) {
                        ezb.deleteCategory(duplicate.id());
                        existing.remove(duplicate);
                    }
                }
                if (leaves.size() > 1) {
                    planned.add(name + " (" + leaves.size() + " duplicate leaf(ves) to remove)");
                }
            }
        }
        return new Categories(created, removed, childIds, planned);
    }

    private static String find(List<EzbClient.CategoryInfo> existing, String name, int categoryType,
                               String parentId) {
        return existing.stream()
            .filter(c -> c.name().equals(name) && c.categoryType() == categoryType
                && parentId.equals(c.parentId()))
            .map(EzbClient.CategoryInfo::id).findFirst().orElse(null);
    }

    /** A row as a {@code TransactionCreateRequest} (§11.2). */
    private Map<String, Object> request(EzbRows.Row row, Map<String, String> childIds,
                                        Map<String, String> accountIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", row.type());
        if (row.type() == EzbRows.BALANCE_MODIFICATION) {
            body.put("categoryId", "0");       // a type-1 row carries no category
        } else {
            String categoryId = childIds.get(row.categoryType() + "|" + row.category());
            if (categoryId == null) {
                throw new Refused("no ezbookkeeping category for '" + row.category() + "' (type "
                    + row.categoryType() + ")");
            }
            body.put("categoryId", categoryId);
        }
        body.put("time", EzbRows.time(row.date(), map.zone()));
        body.put("utcOffset", row.utcOffset());
        body.put("sourceAccountId", accountIds.get(map.get(row.sourceRef()).name()));
        body.put("sourceAmount", row.amount());
        if (row.toRef() != null) {
            body.put("destinationAccountId", accountIds.get(map.get(row.toRef()).name()));
            body.put("destinationAmount", row.amount());
        }
        body.put("comment", row.comment());
        return body;
    }

    /** Poll the async import until idle, within a bounded wait; the count check is the real gate. */
    private void awaitImport() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + IMPORT_TIMEOUT.toNanos();
        while (!ezb.importIdle()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                    "ezbookkeeping's import did not settle within " + IMPORT_TIMEOUT);
            }
            Thread.sleep(50);
        }
    }

    private static int tick(Progress progress, int count) {
        for (int i = 0; i < count; i++) {
            progress.tick();
        }
        return count;
    }

    private static int count(List<EzbRows.Row> rows, int type) {
        return (int) rows.stream().filter(r -> r.type() == type).count();
    }

    /** A fresh id for one import call; see the note at the import site (§11.2). */
    private static String sessionId() {
        return UUID.randomUUID().toString();
    }

    private record Categories(int created, int removed, Map<String, String> childIds,
                              List<String> planned) {}
}
