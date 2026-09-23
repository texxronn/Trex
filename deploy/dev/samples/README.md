# Dev sample ING slices

ING CSV shape (SPEC.md §4): header `Date,Description,Credit,Debit,Balance`,
`dd/MM/uuuu` strict dates, debits negative in the Debit column, one signed
amount per row, balance after the row. A UTF-8 BOM is tolerated.

Ingest in this order — the second file is what makes the matcher fire:

    bin/ingest.sh ing-csv ing-savings samples/ing-savings.csv
    bin/ingest.sh ing-csv ing-orange  samples/ing-orange.csv

What each file exercises:

**ing-savings.csv** (opening balance 2000.00)
- `Fast Transfer to Orange - Receipt 770001` — transfer-shaped, receipt 770001,
  no contra yet → **HELD**, waiting for the orange file.
- two identical `COFFEE CART, SYDNEY` rows, same day and amount — different
  `occ` (§2.5) so they are two distinct transactions, not duplicates → EXTERNAL.
- `Osko payment to plumber` — transfer-shaped, never gets a contra → stays
  **HELD** forever (no aging, §3.4). This is the row to resolve by hand in the
  resolver: MARK_EXTERNAL, or CONFIRM_TRANSFER against another leg.
- `Salary Deposit - Receipt No 998877` — has a receipt but is not
  transfer-shaped → **EXTERNAL**.
- `Internal Transfer to Orange` −400.00 — transfer-shaped, waits for its T3 leg.

**ing-orange.csv** (opening balance 500.00)
- `Fast Transfer from Savings - Receipt 770001` +250.00 — same receipt, opposite
  sign, different account → **T1 ExactTransfer**, confidence EXACT, id
  `TRF-770001`. Journal gets 4 lines: HELD leg, new MATCHED leg, re-appended
  MATCHED leg, TRANSFER.
- `Internal Transfer from Savings` +400.00 on 03/07 — both legs transfer-shaped,
  equal `|amount|`, opposite sign, same currency, within `windowDays = 3` →
  **T3 FuzzyTransfer**, confidence HIGH.
- `Interest "bonus"` — embedded doubled quotes, to prove the CSV reader.

**ing-savings-redelivery.csv** — the bank re-sending 01/07. Rows 1 and 2 are
byte-identical including balance → `DroppedDuplicate`, nothing appended. Row 3
has a changed balance → the *stored* line is re-appended unchanged except for
`flags = [POTENTIAL_DUP]`, and shows up in `GET /review` and in the resolver.
The re-append carries the original balance, so `GET /reconcile` still reports
`ok: true`: the flag asks a human to look, it does not rewrite history. Clear
the flag from the resolver with DISMISS_DUP.

**ing-bad-row.csv** — `31/02/2026` is not a date. The adapter validates the
whole file and sends **nothing**: exit 1, journal untouched, and the bad row
printed as

    ing-bad-row.csv:3 column Date value '31/02/2026': expected dd/mm/yyyy

For the other failure shape — a per-row reject rather than a file reject — send
a good file under an unregistered account:

    bin/ingest.sh ing-csv nope-unknown samples/ing-savings.csv

Every row comes back `Rejected unknown accountRef`, batch `REJECTED`, still
nothing appended.
