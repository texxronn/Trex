package trex.v2.egress.ezb;

import trex.v2.core.Clean;
import trex.v2.core.MerchantStem;
import trex.v2.egress.hub.HubClient.HubUnit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pure unit → ezbookkeeping row mapping (V2-SPEC.md §11.2). No I/O, no clock, no state: the
 * same units and openings produce the same rows. {@code EzbView} (Stage 4) turns a {@link Row} into
 * a {@code TransactionCreateRequest} and posts it.
 *
 * <p><b>Two enums, not one.</b> ezbookkeeping's <em>transaction</em> type is 1 balance modification,
 * 2 income, 3 expense, 4 transfer; its <em>category</em> type is 1 income, 2 expense, 3 transfer.
 * {@link #categoryType} is the second numbering, and it is not the first.
 */
public final class EzbRows {

    // Transaction types (ezbookkeeping's first enum).
    public static final int BALANCE_MODIFICATION = 1;
    public static final int INCOME = 2;
    public static final int EXPENSE = 3;
    public static final int TRANSFER = 4;

    // Category types (ezbookkeeping's second enum — a different numbering).
    public static final int CATEGORY_INCOME = 1;
    public static final int CATEGORY_EXPENSE = 2;
    public static final int CATEGORY_TRANSFER = 3;

    /** Openings are imported first; dated before every real unit so a type-1 row is admissible (§11.2). */
    public static final LocalDate OPENING_DATE = LocalDate.of(2000, 1, 1);

    public static final String OPENING_COMMENT = "trex opening";

    /** ezbookkeeping refuses a comment over 255 characters. */
    public static final int COMMENT_MAX = 255;

    /**
     * One mapped row. {@code category} is a trex master category (null on an opening); {@code
     * categoryType} is its ezbookkeeping category type (null on an opening). {@code amount} is the
     * value the writer posts as {@code sourceAmount}: a <em>signed</em> opening (a debt opens
     * negative) or a positive magnitude for a transaction.
     */
    public record Row(int type, String category, Integer categoryType, String sourceRef, String toRef,
                      long amount, LocalDate date, int utcOffset, String comment) {}

    private EzbRows() {}

    /**
     * Units plus openings ({@code ref -> signed hundredths}) to rows, <b>openings first</b>, in a
     * stable order (openings by ref, then units as given). A TRANSFER unit already carries the
     * paying leg's account in {@code accountRef} (§11.2 Orientation), so its refs pass through and
     * no leg sign is read.
     */
    public static List<Row> of(List<HubUnit> units, Map<String, Long> openings, ZoneId zone) {
        List<Row> out = new ArrayList<>();
        // A type-1 row may only be posted while its account has no other transaction (205010), and
        // it must be dated before the first unit, so openings lead the list (§11.2). Sorted by ref
        // so the order never depends on the map's iteration.
        new TreeMap<>(openings).forEach((ref, opening) -> {
            if (opening != null && opening != 0) {
                out.add(new Row(BALANCE_MODIFICATION, null, null, ref, null, opening, OPENING_DATE,
                    utcOffset(OPENING_DATE, zone), OPENING_COMMENT));
            }
        });
        for (HubUnit unit : units) {
            if ("TRANSFER".equals(unit.unitKind())) {
                out.add(new Row(TRANSFER, unit.category(), categoryType(TRANSFER), unit.accountRef(),
                    unit.toAccountRef(), Math.abs(unit.amount()), unit.date(), utcOffset(unit.date(), zone),
                    comment(unit.rawDescription())));
            } else {
                if (unit.amount() == 0) {
                    continue; // a zero-amount EXTERNAL row is skipped (§11.2)
                }
                // amount < 0 -> expense; amount > 0 -> income: a refund is income, never netted
                // against its charge (operator decision, §11.2).
                int type = unit.amount() < 0 ? EXPENSE : INCOME;
                out.add(new Row(type, unit.category(), categoryType(type), unit.accountRef(), null,
                    Math.abs(unit.amount()), unit.date(), utcOffset(unit.date(), zone),
                    comment(unit.rawDescription())));
            }
        }
        return out;
    }

    /** ezbookkeeping's <em>category</em> type for a <em>transaction</em> type (§11.2). */
    public static int categoryType(int transactionType) {
        return switch (transactionType) {
            case INCOME -> CATEGORY_INCOME;
            case EXPENSE -> CATEGORY_EXPENSE;
            case TRANSFER -> CATEGORY_TRANSFER;
            default -> throw new IllegalArgumentException(
                "no ezbookkeeping category type for transaction type " + transactionType);
        };
    }

    /**
     * The declared wallet's opening: {@code latestCount − Σ units dated ≤ latestAt} (§11.2). The
     * attested count already folds in the movements up to its date, so the opening is the count
     * minus the wallet's <em>signed</em> movement — an ATM inflow adds, a cash spend or an outgoing
     * transfer subtracts. Without this, ezbookkeeping would show the count minus every movement
     * again, i.e. the wallet's unrecorded spend rather than its attested balance. A wallet with no
     * attestation opens at 0.
     */
    public static long declaredOpening(String ref, long latestCount, LocalDate latestAt, List<HubUnit> units) {
        if (latestAt == null) {
            return 0;
        }
        long movement = 0;
        for (HubUnit unit : units) {
            if (unit.date().isAfter(latestAt)) {
                continue;
            }
            if ("TRANSFER".equals(unit.unitKind())) {
                if (ref.equals(unit.accountRef())) {
                    movement -= Math.abs(unit.amount()); // the wallet is the paying leg
                } else if (ref.equals(unit.toAccountRef())) {
                    movement += Math.abs(unit.amount()); // the wallet receives
                }
            } else if (ref.equals(unit.accountRef())) {
                movement += unit.amount(); // an EXTERNAL cash fact is signed
            }
        }
        return latestCount - movement;
    }

    /** The unit's date at 12:00 in the view's zone, as unix seconds (§11.2). */
    public static long time(LocalDate date, ZoneId zone) {
        return date.atTime(12, 0).atZone(zone).toEpochSecond();
    }

    /**
     * The zone's offset in minutes at 12:00 on that date — derived per date, never from "now", so a
     * DST boundary cannot shift a day (§11.2).
     */
    public static int utcOffset(LocalDate date, ZoneId zone) {
        return date.atTime(12, 0).atZone(zone).getOffset().getTotalSeconds() / 60;
    }

    /** {@code MerchantStem.stem(raw) — Clean.clean(raw)}, cut to 255 (§11.2). */
    public static String comment(String rawDescription) {
        String raw = rawDescription == null ? "" : rawDescription;
        return cut(MerchantStem.stem(raw) + " — " + Clean.clean(raw), COMMENT_MAX);
    }

    /** ezbookkeeping's 255-character comment limit (§11.2). */
    public static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
