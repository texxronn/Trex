package trex.v2.egress.ezb;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.HubClient.HubUnit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure §4 mapping: unit + opening -> ezbookkeeping row. No clock, no I/O, deterministic
 * (V2-SPEC.md §11.2).
 */
class EzbRowsTest {

    private static final ZoneId SYD = ZoneId.of("Australia/Sydney");

    private static HubUnit unit(String kind, String from, String to, LocalDate date, long amount,
                                String category, String raw) {
        return new HubUnit(kind.equals("TRANSFER") ? "TRF-x" : "ext", kind, 1, from, to, date, amount,
            "AUD", category, "RULE", kind.equals("TRANSFER") ? "MATCHED" : "EXTERNAL", false, false,
            raw, "h", List.of());
    }

    @Test
    void anExternalSpendIsAnExpenseAndAnIncomeIsAnIncome() {
        List<EzbRows.Row> rows = EzbRows.of(List.of(
            unit("EXTERNAL", "ing-savings", null, LocalDate.of(2026, 9, 1), -1234, "GROCERIES", "COLES"),
            unit("EXTERNAL", "ing-savings", null, LocalDate.of(2026, 9, 2), 2500, "SALARY", "ACME")),
            Map.of(), SYD);

        EzbRows.Row expense = rows.get(0);
        assertEquals(3, expense.type());
        assertEquals(1234, expense.amount());
        assertEquals("ing-savings", expense.sourceRef());
        assertNull(expense.toRef());
        assertEquals("GROCERIES", expense.category());
        assertEquals(2, expense.categoryType());

        EzbRows.Row income = rows.get(1);
        assertEquals(2, income.type());
        assertEquals(2500, income.amount());
        assertEquals(1, income.categoryType());
    }

    @Test
    void anExternalRefundIsIncomeNotNettedAgainstItsCharge() {
        EzbRows.Row refund = EzbRows.of(List.of(
            unit("EXTERNAL", "ing-savings", null, LocalDate.of(2026, 9, 1), 3000, "REFUND", "STORE REFUND")),
            Map.of(), SYD).getFirst();
        assertEquals(2, refund.type(), "a refund is income (operator decision, §11.2)");
        assertEquals(3000, refund.amount());
    }

    @Test
    void aZeroAmountExternalIsSkipped() {
        assertTrue(EzbRows.of(List.of(
            unit("EXTERNAL", "ing-savings", null, LocalDate.of(2026, 9, 1), 0, "X", "ZERO")),
            Map.of(), SYD).isEmpty());
    }

    @Test
    void aTransferTrustsTheDirectionTheUnitCarries() {
        EzbRows.Row transfer = EzbRows.of(List.of(
            unit("TRANSFER", "ing-orange", "ing-savings", LocalDate.of(2026, 9, 1), 500, "TRANSFER", "Move")),
            Map.of(), SYD).getFirst();
        assertEquals(4, transfer.type());
        assertEquals(500, transfer.amount());
        assertEquals("ing-orange", transfer.sourceRef(), "the unit's accountRef is the payer (§11.2)");
        assertEquals("ing-savings", transfer.toRef());
        assertEquals(3, transfer.categoryType());
    }

    @Test
    void openingsComeFirstInRefOrderAndAZeroOpeningIsSkipped() {
        List<EzbRows.Row> rows = EzbRows.of(List.of(
            unit("EXTERNAL", "ing-savings", null, LocalDate.of(2026, 9, 1), -100, "X", "SPEND")),
            Map.of("ing-savings", 4000L, "ing-card", -25000L, "quiet", 0L), SYD);

        assertEquals(3, rows.size(), "two openings plus the one unit; the zero opening is skipped");
        EzbRows.Row card = rows.get(0);
        assertEquals(1, card.type());
        assertNull(card.category());
        assertNull(card.categoryType());
        assertEquals("ing-card", card.sourceRef(), "openings sort by ref");
        assertNull(card.toRef());
        assertEquals(-25000, card.amount(), "a debt opening is signed negative");
        assertEquals(EzbRows.OPENING_DATE, card.date());
        assertEquals("trex opening", card.comment());

        EzbRows.Row savings = rows.get(1);
        assertEquals("ing-savings", savings.sourceRef());
        assertEquals(4000, savings.amount());
        assertEquals(EzbRows.EXPENSE, rows.get(2).type(), "the unit follows the openings");
    }

    @Test
    void aDeclaredWalletsOpeningIsTheCountMinusItsSignedMovements() {
        LocalDate at = LocalDate.of(2026, 9, 3);
        List<HubUnit> units = List.of(
            // An ATM withdrawal: the bank pays, the wallet receives -> +magnitude for the wallet.
            unit("TRANSFER", "ing-orange", "cash-ron", LocalDate.of(2026, 9, 1), 500, "TRANSFER", "ATM"),
            // A recorded cash spend on the wallet: negative.
            unit("EXTERNAL", "cash-ron", null, LocalDate.of(2026, 9, 2), -200, "X", "MANUAL SPEND"),
            // Dated after the attestation: excluded from the opening.
            unit("EXTERNAL", "cash-ron", null, LocalDate.of(2026, 9, 10), -100, "X", "LATER SPEND"));

        // latestCount 700 at latestAt: opening + (+500) + (-200) = 700 -> 400.
        assertEquals(400, EzbRows.declaredOpening("cash-ron", 700, at, units));

        // A transfer the wallet pays out (wallet -> bank) subtracts too.
        List<HubUnit> withOutflow = new java.util.ArrayList<>(units);
        withOutflow.add(unit("TRANSFER", "cash-ron", "ing-orange", LocalDate.of(2026, 9, 3), 300, "TRANSFER", "Deposit"));
        assertEquals(700, EzbRows.declaredOpening("cash-ron", 700, at, withOutflow));

        // A wallet with no attestation opens at 0.
        assertEquals(0, EzbRows.declaredOpening("cash-ron", 0, null, units));
    }

    @Test
    void theCommentIsTheStemAnEmDashAndTheCleanedTextCutTo255() {
        // The stem ends at the 3+-space tail; Clean collapses that run to one space.
        assertEquals("COLES SUPERMARKET — COLES SUPERMARKET 1234 SYDNEY",
            EzbRows.comment("COLES SUPERMARKET   1234 SYDNEY"));

        String long255 = EzbRows.comment("A".repeat(300));
        assertEquals(255, long255.length(), "ezbookkeeping refuses a comment over 255");
        String shortOne = EzbRows.comment("SHOP   Pay");
        assertTrue(shortOne.length() < 255);
        assertEquals("SHOP — SHOP Pay", shortOne);
    }

    @Test
    void theZoneAndOffsetArePerDateSoDstCannotShiftADay() {
        LocalDate beforeDstEnd = LocalDate.of(2026, 4, 5);
        LocalDate afterDstStart = LocalDate.of(2026, 10, 4);
        assertEquals(600, EzbRows.utcOffset(beforeDstEnd, SYD), "AEST +10:00");
        assertEquals(660, EzbRows.utcOffset(afterDstStart, SYD), "AEDT +11:00");

        assertEquals(LocalDateTime.of(2026, 4, 5, 2, 0).toEpochSecond(ZoneOffset.UTC),
            EzbRows.time(beforeDstEnd, SYD));
        assertEquals(LocalDateTime.of(2026, 10, 4, 1, 0).toEpochSecond(ZoneOffset.UTC),
            EzbRows.time(afterDstStart, SYD));

        // A row carries the offset of its own date, not the current one.
        EzbRows.Row row = EzbRows.of(List.of(
            unit("EXTERNAL", "ing-savings", null, afterDstStart, -100, "X", "SPEND")), Map.of(), SYD).getFirst();
        assertEquals(660, row.utcOffset());
        assertEquals(EzbRows.time(afterDstStart, SYD), EzbRows.time(row.date(), SYD));
    }

    @Test
    void theCategoryTypeEnumIsItsOwnNumbering() {
        assertEquals(1, EzbRows.categoryType(2), "transaction income -> category income");
        assertEquals(2, EzbRows.categoryType(3), "transaction expense -> category expense");
        assertEquals(3, EzbRows.categoryType(4), "transaction transfer -> category transfer");
        assertThrows(IllegalArgumentException.class, () -> EzbRows.categoryType(1));
    }
}
