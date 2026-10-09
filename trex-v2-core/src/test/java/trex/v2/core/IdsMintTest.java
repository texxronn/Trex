package trex.v2.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-batch minting rule (V2-PROPOSAL.md §6.1; V2-REVIEW-FIXES-PLAN.md §4.3). */
class IdsMintTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 3);

    private static Ids.Row row(long amount, String raw, String receipt) {
        return new Ids.Row("ing-credit-card", DAY, amount, raw, receipt);
    }

    @Test
    void aUniqueReceiptMintsItsNaturalKey() {
        assertEquals(List.of(new Ids.Minted(Ids.naturalKey("ing-credit-card", DAY, "171689"), 0)),
            Ids.mint(List.of(row(-6401, "SHOP", "171689"))));
    }

    @Test
    void identicalRowsSharingAReceiptStayOneNaturalKey() {
        String nk = Ids.naturalKey("ing-credit-card", DAY, "171689");
        assertEquals(List.of(new Ids.Minted(nk, 0), new Ids.Minted(nk, 0)),
            Ids.mint(List.of(row(-6401, "SHOP", "171689"), row(-6401, "SHOP", "171689"))));
    }

    @Test
    void differentRowsSharingAReceiptMintContentIds() {
        List<Ids.Minted> minted = Ids.mint(List.of(
            row(192, "Fee Rebate", "171689"), row(-192, "Fee", "171689"), row(-6401, "SHOP", "171689")));
        assertEquals(new Ids.Minted(Ids.contentHash("ing-credit-card", DAY, 192, "Fee Rebate", 0), 0), minted.get(0));
        assertEquals(new Ids.Minted(Ids.contentHash("ing-credit-card", DAY, -192, "Fee", 0), 0), minted.get(1));
        assertEquals(new Ids.Minted(Ids.contentHash("ing-credit-card", DAY, -6401, "SHOP", 0), 0), minted.get(2));
    }

    @Test
    void aCollidedRowSharesTheOccCounterWithReceiptlessRows() {
        // A receipt-less row of identical content on the same day must not mint the same id.
        List<Ids.Minted> minted = Ids.mint(List.of(
            row(-192, "Fee", null), row(-192, "Fee", "171689"), row(-6401, "SHOP", "171689")));
        assertEquals(0, minted.get(0).occ());
        assertEquals(1, minted.get(1).occ());
        assertEquals(Ids.contentHash("ing-credit-card", DAY, -192, "Fee", 1), minted.get(1).id());
    }

    @Test
    void theSameReceiptOnAnotherDayOrAccountIsNotACollision() {
        List<Ids.Minted> minted = Ids.mint(List.of(
            row(-192, "Fee", "171689"),
            new Ids.Row("ing-credit-card", DAY.plusDays(1), -6401, "SHOP", "171689"),
            new Ids.Row("ing-orange", DAY, -6401, "SHOP", "171689")));
        assertEquals(Ids.naturalKey("ing-credit-card", DAY, "171689"), minted.get(0).id());
        assertEquals(Ids.naturalKey("ing-credit-card", DAY.plusDays(1), "171689"), minted.get(1).id());
        assertEquals(Ids.naturalKey("ing-orange", DAY, "171689"), minted.get(2).id());
    }
}
