# V2-INBOX-CLEAR-PLAN.md

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is the
> build order, and the record of why. The outcome lives in the spec (§9.3, §16).

> **Status:** built (2026-10-17).
> **Goal:** the staging inbox can be *cleared*. Today it can only grow.
> **Out of scope:** a retention policy, a scheduled purge, or any change to what `done/` and
> `failed/` mean to the inbox sweep. Nothing here touches the log, the index or a derived table.

---

## 0. Summary

| # | Change | Friction it removes | Size | Writes the log? |
|---|---|---|---|---|
| I-1 | **`Clear` removes the upload** (delete the file and its sidecar) | `Clear` filed a row into `done/` and left it listed, so the list never shrank | S | no |
| I-2 | **Every row can be cleared**, not only ingested ones | an upload you decided *not* to ingest had no button at all — it was stuck in the list | S | no |
| I-3 | **The confirm names the risk** (how many have no other copy) | deleting a never-ingested upload destroys the only copy of that statement | S | no |
| I-4 | **Clear selected** + a select-all box | the clutter is dozens of rows, one click each | S | no |

**Order:** I-1 → I-2 → I-3 → I-4; they are one change, and I-3 is what makes I-1 and I-2 honest.

**Invariants touched:** none. The log holds no staging state, so nothing is appended and nothing is
revoked; the evidence store is the durable copy of the bytes; every derived table is untouched.
**Downstream (ezbookkeeping):** none — no derive input, category, identity rule or unit changes.

---

## 1. Why

The Jobs page's staging inbox is a list of **files**, and its only action worked like this:

- The row's `Clear` (`web/js/jobs.js`) calls `POST /api/jobs/staging/clear`, which the runner
  implements as `staging.markDone(name)` — a `mv` into `done/`.
- `StagingStore.list()` reads **three** directories: the inbox, `done/` and `failed/`. So the row it
  just moved stays in the list, relabelled `cleared`.
- `Clear` is only rendered on rows whose bytes are on facts (`file.ingested`), so an upload that was
  never ingested has no control at all.

Net effect, measured on Dev1 the day it was reported (2026-10-17):

```
inbox (staged)  12     ← Transactions.csv, Transactions__1_.csv, Transactions__2_.csv, …
done/            4     ← shown as "cleared", permanently
failed/          0
```

Sixteen rows, one of which the operator could not get rid of and none of which the `Clear` button
would ever remove. "Clear" did not clear.

---

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| I-1 | **`Clear` deletes the upload** (the file and its `.meta` sidecar), wherever it sits — inbox, `done/` or `failed/`. `done/` becomes what the *sweep* produces, not what `Clear` produces. | The button's name is a promise the operator already believes. The row must leave the list. |
| I-2 | **Every row gets `Clear`.** | The stuck rows are the never-ingested ones; a control that only appears on rows you no longer need to clear is the wrong shape. |
| I-3 | **One confirm, and it states the cost.** When the selection contains files that were never ingested it says how many, that they are not in the log, and that this removes the only copy — naming them. When every file is already on facts it says only that the evidence store keeps the bytes. | This is the one destructive operation in the inbox. The store cannot warn: it does not read the log, so only the view knows which bytes are durable. |
| I-4 | **`Clear selected` in the toolbar, with a select-all box in the table head.** | The reported clutter is a dozen duplicate uploads; per-row clicking is the friction. |
| I-5 | **The path stays `/api/jobs/staging/clear`** (hub proxy and `api.clearStaged` unchanged); the runner's operation becomes `StagingStore.discard`. `markDone`/`markFailed` stay for the `ingest-inbox` sweep. | The wire path and the button agree on the word; renaming the route would churn the hub proxy for nothing. `markDone` is the sweep's filing, and that stays a filing. |

**Rejected:** *hide the cleared rows from the list instead of deleting* — it leaves the twelve
un-ingested duplicates exactly where they are, which is the bulk of the complaint. *Refuse to clear
a never-ingested upload ("ingest it first")* — it makes discarding a mistake a two-step ritual and
still leaves the operator with no way to say "I do not want this file".

---

## 3. Design

**The runner** (`trex-v2-runner`):

- `StagingStore.discard(String name)` — `deleteIfExists` on the file and on its `.meta` sidecar, in
  each of the inbox, `done/` and `failed/`; `true` when something went. Names still go through
  `safeResolve`, so a traversal attempt is an `IllegalArgumentException` (the API's `400`).
- `POST /staging/clear?name=` → `discard`, answering `{cleared: bool}` — `200` when removed, `404`
  when no such file. `markDone`/`markFailed` remain the sweep's filing (`JobCatalogue` line ~300).

**The hub** (`trex-v2-hub`): no change. `/api/jobs/staging/clear` already proxies by name; the hub
still stores nothing and still derives the `ingested` tick from the log's evidence ids, which is the
flag the confirm needs.

**The view** (`web/js/jobs.js`):

- `renderStaging` adds a `Clear selected` button beside `Ingest selected`, and a select-all box in
  the head; `syncSelection()` keeps both bulk buttons and the box's indeterminate state in step with
  the per-row ticks.
- Every row gets `Clear`; both it and the bulk action go through one `clearFiles(files)`, which
  confirms with `clearMessage(files)` first.
- The `done` row's tooltip changes from "cleared from the inbox" to the truth: the sweep filed it,
  and `Clear` removes the file.

**What is deliberately not touched:** the log, the index, `ingest-inbox`'s exit-code filing, the
evidence store, and the `statements.yaml` name map.

---

## 4. Acceptance tests

**Unit** (`StagingStoreTest`): `discard` removes an inbox file and its sidecar; removes a file from
`done/` (the state `Clear` used to leave behind) and its sidecar; returns `false` for a name that is
not there; refuses a traversal name (`../…`, `a/b`) with `IllegalArgumentException`.

**Manual (Dev1, the reported state):**

1. Jobs shows the 16 rows above; every row has `Clear` (previously only the 4 ingested ones).
2. Tick a never-ingested row → `Clear` → the confirm names it, says it is not in the log and that
   this removes the only copy → Cancel leaves it alone, OK removes the row.
3. Select-all → `Clear selected` → the confirm names the count and how many are un-ingested → OK →
   the list is empty, `staged (inbox) 0 / done/ 0 / failed/ 0` in the volume, and the log's line
   count and review queue are unchanged.
4. `POST /api/jobs/staging/clear` again for a name just cleared → `404`.
5. Re-upload the same statement, ingest it, then `Clear` → row goes, the ingest history row and the
   evidence are still there, and a `trex index --rebuild` changes nothing.
