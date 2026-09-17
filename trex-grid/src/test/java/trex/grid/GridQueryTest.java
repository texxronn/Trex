package trex.grid;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GridQueryTest {

    @Test
    void defaults() {
        GridQuery q = GridQuery.parse(null);
        assertEquals(GridQuery.View.TRANSACTIONS, q.view());
        assertEquals(List.of(new GridQuery.SortKey("n", true)), q.sort());
        assertEquals(1, q.page());
        assertEquals(50, q.size());
        assertNull(q.asOfN());
        assertEquals(new GridQuery.Filters(null, null, null, null, null, null), q.filters());
    }

    @Test
    void decodesAndNormalises() {
        GridQuery q = GridQuery.parse("q=%20Fast%20Transfer%20&account=ing-savings&sort=date:asc,amount:desc&asOfN=12");
        assertEquals("fast transfer", q.filters().q());
        assertEquals("ing-savings", q.filters().account());
        assertEquals(List.of(new GridQuery.SortKey("date", false), new GridQuery.SortKey("amount", true)), q.sort());
        assertEquals(12L, q.asOfN());
    }

    @Test
    void rejectsInvalidParameters() {
        for (String bad : List.of("view=pivot", "sort=balance:asc", "sort=n", "sort=n:up", "sort=n:asc,n:desc",
            "page=0", "size=0", "size=501", "page=x", "asOfN=-1", "asOfN=abc", "state=DONE", "type=FEE",
            "from=01/06/2026", "q=a&q=b")) {
            assertThrows(IllegalArgumentException.class, () -> GridQuery.parse(bad), bad);
        }
    }
}
