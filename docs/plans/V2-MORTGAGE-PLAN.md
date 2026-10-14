# V2-MORTGAGE-PLAN.md

> **Status:** proposed; not started. `V2-SPEC.md` is the specification; this file is the build order
> for one change to it. Where this file and the spec disagree, the spec wins, and the spec is
> amended in the same change (`AGENTS.md`). The design was drafted by the architect on 2026-10-14
> (`ses_ec5ca3644ffetS13OqRJjhA4op`); the operator's decisions are §3.

**Authority:** `V2-SPEC.md` §6.5, §6.7, §9 (modes, refdata), §11.1, §12; `docs/plans/V2-CASH-PLAN.md`;
`docs/plans/V2-EXPECTED-UX-PLAN.md`; `AGENTS.md`.
**Adds to the decision set:** none (a new action would be a reflow; this is a read + view-config).

---

## 1. The problem

The ING facility — offset `ing-loan-offset`, loans `ing-mortgage-simplifier` and `ing-variable-rate`,
plus four declared commitments (two `loan` repayments, two `interest_paid` charges) — is already
modelled, but there is no surface that answers the operator's questions in one place: what is my
**liquidity** (the offset), per loan what have I **repaid** and what **interest** have I been
charged, and — the finding that started this — **is the offset actually working**? The data is in
the log; only its presentation is missing. Two facts make an honest view possible and two
inferences must not be presented as facts.

## 2. What the view can show, and what it must not claim

| Element | Source | Kind |
|---|---|---|
| Offset balance + statement date | `/api/reconcile` closing, `/api/accounts` last | **fact** |
| Each loan balance + its own statement date | same | **fact** |
| Repayments per loan (count, span, total) | `/api/commitments/activity?id=` for the `loan` commitment | **fact** |
| Recorded interest per loan (count, span, total) | `/api/commitments/activity?id=` for the `interest_paid` commitment | **fact** |
| **Principal = repayments − recorded interest** | arithmetic | **estimate** |
| Balance change `closing − opening`, and the **residual** against principal | `/api/reconcile` opening/closing | arithmetic (exposes an unexplained difference) |
| Offset sheltering a named loan, and the **idle** balance beyond it | the declared facility mapping | **declaration** (config) + arithmetic |
| Cost of the idle offset | declared observed rate × idle, else the latest recorded charge scaled to the idle | **estimate**, labelled |

The honest caveats, stated in the view: the repayment− interest principal is an *estimate from
recorded movements*, not an observed transaction; a residual (the variable loan carries a ~$470
gap between the two) is **shown, never folded into principal**; no rate, no property value and no
equity are inferred from charges. Offset attribution is an **operator declaration**, not a guess
from near-zero interest.

## 3. Operator decisions (2026-10-14)

| Id | Decision | Why |
|---|---|---|
| **M-1** | A dedicated **Mortgage** mode, **not** a merged Liabilities/Net-position view. | The operator's words: *"As for Trex — we should not merge."* A general liability model would delay this and need a classification the read model does not carry. |
| **M-2** | The offset→loan link and the facility membership are a **declared** config mapping, not inferred. | The whole insight is *which* loan the offset shelters; inferring it from charges is exactly the mistake the exercise is meant to catch. |
| **M-3** | It is **presentation only**: no `derive()` change, no new table, **no Firefly impact**. | Commitments are not projected (§11.1); balances and activity already exist. |
| **M-4** | Principal is shown as an **estimate** with its residual; no rate/equity/payoff claims. | The variable loan does not reconcile; the bank's fixed→variable step is a charge history, not a rate. |
| **M-5** | The config is **view-config**: loaded, exposed by refdata, but **not** hashed into `configRevision` and **not** a derive input. | It groups accounts for one screen and moves no derived state; `--rebuild` and the state hash must not move because of it. |

## 4. The change

### 4.1 Config: `deploy/config/mortgages.yaml` (new, §12)

One or more facilities; each names an offset account, its loans, and — per loan — the two
commitment ids that classify its repayments and its interest. Optional `observedRatePct` enables the
idle-offset cost estimate. Account refs are validated at load (unknown ref = startup error);
commitment ids are **not** validated against the log at load (they are decisions, not config) — the
view degrades to an empty series when a commitment is absent or retired.

```yaml
mortgages:
  - name: "ING"
    offset: ing-loan-offset
    offsetAppliesTo: ing-variable-rate   # the loan the offset shelters; omit to model the whole facility
    observedRatePct: 6.15          # optional; enables the idle-offset cost estimate
    loans:
      - account: ing-mortgage-simplifier
        label: "Simplifier"
        repaymentCommitment: ing-simplifier-repayment
        interestCommitment: ing-simplifier-interest
      - account: ing-variable-rate
        label: "Variable"
        repaymentCommitment: ing-variable-repayment
        interestCommitment: ing-variable-interest
```

### 4.2 Read: `/api/refdata` gains `mortgages` (§9)

`RefdataResponse` carries the loaded facilities (account refs, labels, commitment ids, the optional
rate). This is the same pattern as accounts/users: config, not index, is the source.

### 4.3 Web: `mortgage.js` (new mode, §9.3)

A tenth-and-one — **eleven modes**. It reads `ctx.refdata.mortgages`, then composes
`/api/accounts` + `/api/reconcile` (balances, dates, opening/closing) and, per commitment id,
`/api/commitments/activity?id=`. Layout:

```
LIQUIDITY
  Offset  +$217,313.10 (7 Oct)   shelters Variable  ·  idle ~$101,600
DEBT (per loan)
  Simplifier  −$328,914.79 (30 Sep)  ·  Variable  −$115,752.28 (30 Sep)
MOVEMENTS (per loan)
  repayments  n · span · total   │   recorded interest  n · span · total
  principal (estimate)  = repayments − interest   ·   balance change   ·   residual
```

Signed magnitudes with explicit direction, matching Expected's convention (§9.3). The idle-offset
cost line is always labelled an estimate: with `observedRatePct` set it is the declared rate × idle,
otherwise it is estimated from the latest recorded charge, and with neither a rate nor a charge on
record it is omitted. No numbers are netted across loans with different statement dates.

### 4.4 Deploy/ops

`mortgages.yaml` joins the seeded config set: the `init` service, `ConfigDrift.FILES`,
`deploy/v2/sync-config.sh`, both compose files, and `docs/DEPLOYMENTS.md` / `deploy/v2/README.md`.
An absent file is valid (no facilities, no Mortgage content).

## 5. Invariant check

| Invariant | Holds because |
|---|---|
| `derive()` pure | untouched; the config is not a derive input and is not hashed |
| Facts / decisions / ingest events only | nothing new is written; the mapping is view-config and commitments stay decisions |
| Decisions win | the view reads the effective commitments and their current facts, as every read does |
| Nothing deleted; tables disposable | no table is added; the view is a composition of existing reads |
| No category on a transaction | untouched |
| No `externalId` rewrite | untouched |
| Egress | **no impact** — no unit, account type or balance moves; commitments are not projected |

## 6. Blast radius

| module | file | change |
|---|---|---|
| hub | `Mortgages.java` (new) | the facility model + validation, read once at hub startup |
| hub | `api/RefdataResponse.java` | `List<MortgageJson> mortgages` + a six-arg convenience constructor |
| hub | `HubService.java` | load the facilities at startup and map them into refdata |
| hub | `ConfigDrift.java` | add `mortgages.yaml` to `FILES` |
| web | `js/mortgage.js` (new), `js/app.js`, `index.html`, `app.css` | the mode, nav and styles |
| deploy | `deploy/config/mortgages.yaml`, `compose.yml`, `compose.server.yml`, `sync-config.sh` | seed the file |
| docs | `V2-SPEC.md` §9, §11.1, §12, §17; `CHANGELOG.md`; `docs/DEPLOYMENTS.md`; `deploy/v2/README.md` | the amendments |

The loader lives in the hub, not in core `ConfigLoader`: `mortgages.yaml` is a view input, and
routing it through `DeriveConfig` would risk hashing it into `configRevision` and re-deriving on an
edit. Keeping it hub-only is what makes M-5 structural rather than a promise.

**Tests:** `MortgagesTest` (loading, defaults and each refusal: an unknown account, a loan that is
also the offset, an empty facility), a `HubApiTest` assertion that `/api/refdata` carries the
mapping, and the `ConfigDriftTest`/`HubConfigDriftApiTest` file-count updates. No `derive()` test
moves — the point of M-3/M-5 is that none can.

## 7. Stages

Landed as: this plan and the spec (§9, §12, §17) as their own commits, then one implementation commit
covering 3–6. The tree is green after each.

1. **This plan** (docs commit).
2. **Spec** — §9 (the Mortgage mode; the refdata field), §12 (`mortgages.yaml`, view-config, not
   hashed), §17 (record).
3. **Config** — `Mortgages` (hub-only), validation; `mortgages.yaml`.
4. **Refdata** — the response field, the mapping, the test constructors.
5. **Web** — `mortgage.js`, nav, styles.
6. **Deploy and docs** — the seed lists, `DEPLOYMENTS.md`, `CHANGELOG.md`.

## 8. Acceptance

- `/api/refdata` returns the declared facilities; a missing `mortgages.yaml` yields an empty list and
  the mode renders an empty state, not an error.
- An unknown account ref, or a loan equal to its facility's offset, is a startup error naming the
  file.
- The mode shows the offset and each loan balance with its own statement date; per loan the
  repayment and interest series with count, span and total; principal as a labelled estimate; and the
  residual beside it.
- The idle-offset magnitude is shown; the cost line appears only with a declared rate and is labelled
  an estimate.
- `configRevision`, `DERIVE_VERSION`, `HASH_VERSION` and every derived table are unchanged by adding
  or editing `mortgages.yaml`; the Firefly plan is byte-identical.

## 9. Rollback

Revert the PRs. The view-config file is inert without the code; removing it removes the mode's
content, and nothing in the log, the index or Firefly depends on it.
