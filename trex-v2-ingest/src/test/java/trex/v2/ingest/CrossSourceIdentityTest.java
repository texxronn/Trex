package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import trex.v2.ingest.cba.CbaCsv;
import trex.v2.ingest.cba.CbaPdf;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §15.9: when two sources publish the same row, they must mint the same id. cba-pdf and cba-csv are
 * the template for every future source pair — the PDF is the cross-source test that keeps identity
 * from drifting between the bank's two exports of the same account.
 */
class CrossSourceIdentityTest {

    @Test
    void thePdfAndCsvOfTheSameRowMintTheSameId() {
        String account = "cba-netsaver";
        String csv = "01/07/2026,-75.00,COLES 1234,1000.00\n";
        String pdf = """
            CommBank
            Date Transaction details Amount Balance
            01 Jul 2026  COLES 1234  -$75.00  $1,000.00
            Created 02/07/26
            """;

        FactDraft fromCsv = new CbaCsv().parse(csv.getBytes(StandardCharsets.UTF_8), "a.csv", account)
            .candidates().getFirst();
        FactDraft fromPdf = CbaPdf.parse(pdf, "a.pdf", account).candidates().getFirst();

        assertEquals(fromCsv.amount(), fromPdf.amount());
        assertEquals(fromCsv.rawDescription(), fromPdf.rawDescription());
        assertEquals(Reparse.mintIds(List.of(fromCsv)),
            Reparse.mintIds(List.of(fromPdf)),
            "the two sources mint one id for one row");
    }
}
