package trex.gateway.grid;

import org.junit.jupiter.api.Test;
import trex.core.CanonicalEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GridIndexTest {

    private final GridIndex index = new GridIndex(Lines.categorizer());
    private final GridData data = Lines.sample();

    private GridIndex.Page q(String query) {
        return index.query(data, GridQuery.parse(query));
    }

    private static List<Long> ns(GridIndex.Page p) {
        return p.rows().stream().map(CanonicalEvent::n).toList();
    }

    @Test
    void transactionsViewIsLatestLinePerIdSortedByNDescByDefault() {
        GridIndex.Page p = q("");
        assertEquals(List.of(9L, 8L, 7L, 6L, 5L, 2L), ns(p));
        assertEquals(6, p.total());
        assertEquals(9, p.asOfN());
        assertEquals(6, data.transactions());
        assertEquals(List.of("bw-usd", "cba", "ing"), data.accounts());
    }

    /**
     * SPEC §5.6: the grid derives each row's category; the journal has none. Sample data:
     * n2 Salary → SALARY, n8 "Coffee" → GROCERIES, legs of TRF-x (n5, n6) and the TRANSFER
     * line itself (n7) are structural, n9 "coffee beans" → GROCERIES.
     */
    @Test
    void everyRowCarriesADerivedCategory() {
        GridIndex.Page p = q("");
        assertEquals("SALARY", p.categories().get(2L).category());
        assertEquals("RULE", p.categories().get(2L).origin());
        assertEquals("GROCERIES", p.categories().get(8L).category());
        assertEquals("TRANSFER", p.categories().get(7L).category());
        assertEquals("STRUCTURAL", p.categories().get(7L).origin());
        // A leg of a matched transfer is structural too, whatever its description says.
        assertEquals("TRANSFER", p.categories().get(5L).category());
        assertEquals("rule #1 (SALARY)", p.categories().get(2L).why());
    }

    @Test
    void categoryFiltersRowsIncludingTheUncategorizedWorklist() {
        assertEquals(List.of(9L, 8L), ns(q("category=GROCERIES")));
        assertEquals(List.of(2L), ns(q("category=SALARY")));
        assertEquals(List.of(7L, 6L, 5L), ns(q("category=TRANSFER")));
        assertEquals(List.of(), ns(q("category=UNCATEGORIZED")));
        assertEquals(List.of(), ns(q("category=NOT_A_CATEGORY")));
    }

    @Test
    void rowsSortByDerivedCategory() {
        // GROCERIES < SALARY < TRANSFER, ties by n ascending.
        assertEquals(List.of(8L, 9L, 2L, 5L, 6L, 7L), ns(q("sort=category:asc")));
        assertEquals(List.of(5L, 6L, 7L, 2L, 8L, 9L), ns(q("sort=category:desc")));
    }

    @Test
    void journalViewHasEveryLine() {
        GridIndex.Page p = q("view=journal&sort=n:asc");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), ns(p));
        assertEquals(List.of(), p.totals());
    }

    @Test
    void pinnedSnapshotIgnoresLaterLines() {
        assertEquals(List.of(4L, 3L, 2L, 1L), ns(q("asOfN=4")));
        assertEquals(4, q("asOfN=4").asOfN());
        assertEquals(9, q("asOfN=999").asOfN());
        assertEquals(List.of(), ns(q("asOfN=0")));
    }

    @Test
    void pagingSlicesTheSortedResult() {
        assertEquals(List.of(9L, 8L), ns(q("size=2&page=1")));
        assertEquals(List.of(7L, 6L), ns(q("size=2&page=2")));
        assertEquals(List.of(5L, 2L), ns(q("size=2&page=3")));
        GridIndex.Page beyond = q("size=2&page=9");
        assertEquals(List.of(), ns(beyond));
        assertEquals(6, beyond.total());
    }

    @Test
    void multiColumnSortWithNTieBreakAndNullsLast() {
        // amount asc: -999(9) -500(5 a) -300(8 c) 500(6 d) 500(7 TRF) 1200(2)
        assertEquals(List.of(9L, 5L, 8L, 6L, 7L, 2L), ns(q("sort=amount:asc")));
        // accountRef asc then amount desc: bw-usd(9) cba: 1200(2), 500(6) · ing: 500 TRF(7), -300(8), -500(5)
        assertEquals(List.of(9L, 2L, 6L, 7L, 8L, 5L), ns(q("sort=accountRef:asc,amount:desc")));
        // toAccountRef is null except the TRANSFER: nulls last in both directions, ties by n asc
        assertEquals(List.of(7L, 2L, 5L, 6L, 8L, 9L), ns(q("sort=toAccountRef:asc")));
        assertEquals(List.of(7L, 2L, 5L, 6L, 8L, 9L), ns(q("sort=toAccountRef:desc")));
        // case-insensitive text: "coffee beans" vs "Coffee" etc.
        assertEquals(List.of(8L, 9L), ns(q("sort=description:asc&q=coffee")));
    }

    @Test
    void filtersAndSearch() {
        assertEquals(List.of(7L, 6L, 2L), ns(q("account=cba")));
        assertEquals(List.of(7L, 6L, 5L), ns(q("state=MATCHED")));
        assertEquals(List.of(7L), ns(q("type=TRANSFER")));
        assertEquals(List.of(7L, 6L, 5L), ns(q("from=2026-06-02&to=2026-06-02")));
        assertEquals(List.of(9L, 8L), ns(q("q=COFFEE")));
        assertEquals(List.of(7L), ns(q("q=note")));
        assertEquals(List.of(7L), ns(q("q=trf-")));
        assertEquals(List.of(4L, 1L), ns(q("view=journal&state=HELD")));
    }

    @Test
    void totalsPerCurrencyExcludeTransfers() {
        GridIndex.Page p = q("");
        // AUD: a -500 + d 500 + c -300 + b 1200 = 900 over 4 rows (TRANSFER excluded); USD: -999
        assertEquals(List.of(new GridIndex.Total("AUD", 900, 4), new GridIndex.Total("USD", -999, 1)), p.totals());
        assertEquals(List.of(new GridIndex.Total("AUD", -800, 2)), q("account=ing&type=WITHDRAWAL").totals());
    }

    @Test
    void resultsAreCachedPerQueryAndPagesShareThem() {
        GridIndex.Page p1 = q("size=2&page=1");
        GridIndex.Page p2 = q("size=2&page=1");
        assertSame(p1.totals(), p2.totals());
    }

    /**
     * A projector asks "what moved since n?" and must be told about a transaction whose STATE
     * changed, even though it saw the transaction before. In the transactions view the row is the
     * latest line, so filtering on that line's n is what makes a HELD row becoming EXTERNAL show
     * up — the change is the news, not the first sighting.
     */
    @Test
    void sinceNSelectsByTheLatestLine() {
        long head = data.lines().getLast().n();
        assertEquals(0, index.query(data, query("sinceN=" + head)).total(),
            "nothing is newer than the head");

        int all = index.query(data, query(null)).total();
        assertEquals(all, index.query(data, query("sinceN=0")).total(),
            "everything is newer than 0");
    }

    private static GridQuery query(String raw) {
        return GridQuery.parse(raw);
    }
}
