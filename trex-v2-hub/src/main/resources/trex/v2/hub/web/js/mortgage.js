// Mortgage mode (V2-SPEC.md §9.3, §12): one declared facility's offset and loans, composed from the
// existing reads — /api/refdata (the facility mapping), /api/ledger (each account's statement
// running balance and its ends), /api/reconcile (a reconciled account's closing) and
// /api/commitments/activity (the repayments and interest a commitment claims). Nothing is derived
// and no endpoint is added.
//
// Facts and estimates are kept apart: balances, movements and the residual are facts; "principal"
// is an estimate from recorded movements and its residual is shown, never folded in; the idle
// offset's cost is an estimate and states its assumptions. Balances with different statement dates
// are never netted into one headline.

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, scroll } from './dom.js';
import { date, money } from './format.js';
import { direction } from './direction.js';
import { reportError } from './toast.js';

let host;
let ctx;
let data = null;             // the composed facilities
let loadGeneration = 0;      // supersedes an in-flight load when a newer one starts
let errorBar;

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: load };
}

async function load() {
  const generation = ++loadGeneration;
  try {
    const [refdata, reconcile] = await Promise.all([api.refdata(), api.reconcile()]);
    const facilities = (refdata && refdata.mortgages) || [];

    const refs = new Set();
    const ids = new Set();
    for (const f of facilities) {
      if (f.offset) refs.add(f.offset);
      for (const l of f.loans || []) {
        if (l.account) refs.add(l.account);
        if (l.repaymentCommitment) ids.add(l.repaymentCommitment);
        if (l.interestCommitment) ids.add(l.interestCommitment);
      }
    }
    // Each account's ledger carries the statement's running balance — the only reliable balance
    // when a chain is broken (the offset's reconcile answers zero). Each commitment is one read; a
    // missing or retired one answers empty rather than failing the whole view.
    const ledgers = new Map();
    const activities = new Map();
    await Promise.all([
      ...[...refs].map(async (ref) => {
        try {
          ledgers.set(ref, ((await api.ledger({ account: ref, limit: '5000' })).rows) || []);
        } catch {
          ledgers.set(ref, []);
        }
      }),
      ...[...ids].map(async (id) => {
        try {
          activities.set(id, await api.activity(id));
        } catch {
          activities.set(id, []);
        }
      }),
    ]);
    if (generation !== loadGeneration) return; // superseded: a newer load owns the view
    data = compose(facilities, reconcile, activities, ledgers);
    errorBar.hidden = true;
  } catch (error) {
    if (generation !== loadGeneration) return;
    errorBar.textContent = error.message || 'failed to load the mortgage view';
    errorBar.hidden = false;
    return;
  }
  render();
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  host.append(errorBar);
  if (!data) {
    host.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  if (!data.length) {
    host.append(el('p', { class: 'muted' },
      'No mortgage facility is declared. Add one to mortgages.yaml (V2-SPEC.md §12).'));
    return;
  }
  for (const facility of data) host.append(facilityView(facility));
}

// ---- the composed model -------------------------------------------------------------------------

function compose(facilities, reconcile, activities, ledgers) {
  const byRef = new Map((reconcile.accounts || []).map((a) => [a.accountRef, a]));

  // One account's balance and the ends of its ledger. The ledger's rows are ordered by (date, n),
  // so the newest row is the statement's running balance and the oldest is the account's first
  // recorded movement. A reconciled account's closing is authoritative and wins; a broken chain
  // (the offset's) reports zero, so the ledger's latest stands.
  function account(ref) {
    const rows = [...(ledgers.get(ref) || [])]
      .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : (a.n - b.n)));
    const r = byRef.get(ref);
    const reconciled = !!(r && r.status === 'reconciled');
    const first = rows.length ? rows[0] : null;
    const last = rows.length ? rows[rows.length - 1] : null;
    return {
      balance: reconciled ? r.closing : (last ? last.balance : null),
      first: first ? first.balance : null,
      date: last ? last.date : null,
      reconciled,
    };
  }

  return facilities.map((f) => {
    const o = account(f.offset);
    const offset = Math.max(0, o.balance || 0);
    const loans = (f.loans || []).map((l) => {
      const a = account(l.account);
      const repaid = series(activities.get(l.repaymentCommitment));
      const interest = series(activities.get(l.interestCommitment));
      // Principal is repayments less recorded interest: an estimate, not an observed movement.
      const principal = repaid.total - interest.total;
      // The balance change since the account's first recorded movement; the residual is what
      // neither principal nor a refund accounts for, surfaced rather than folded in.
      const change = a.balance === null || a.first === null ? null : a.balance - a.first;
      return {
        ...l,
        balance: a.balance,
        balanceDate: a.date,
        change,
        reconciled: a.reconciled,
        repayments: repaid,
        interest,
        principal,
        residual: change === null ? null : change - principal,
      };
    });
    const debt = loans.reduce((n, l) => n + Math.abs(l.balance || 0), 0);
    // What the offset actually shelters: the declared loan when the bank links it to one, else the
    // whole facility. The balance beyond it is idle — the finding this view exists to show.
    const sheltered = f.offsetAppliesTo
      ? loans.find((l) => l.account === f.offsetAppliesTo) || null
      : null;
    const shelteredDebt = sheltered ? Math.abs(sheltered.balance || 0) : debt;
    return {
      ...f,
      offsetBalance: o.balance,
      offsetDate: o.date,
      loans,
      debt,
      offset,
      sheltered,
      shelteredDebt,
      working: Math.min(offset, shelteredDebt),
      idle: Math.max(0, offset - shelteredDebt),
    };
  });
}

/** One activity series: unsigned totals (magnitudes), the span, and the newest row for the estimate. */
function series(rows) {
  const current = (rows || []).filter((a) => !a.excluded);
  const total = current.reduce((n, a) => n + Math.abs(a.amount || 0), 0);
  const newest = current.length ? current[current.length - 1] : null;
  return {
    rows: current,
    count: current.length,
    total,
    first: current.length ? current[0].date : null,
    last: newest ? newest.date : null,
    lastAmount: newest ? Math.abs(newest.amount || 0) : null,
  };
}

// ---- the facility -------------------------------------------------------------------------------

function facilityView(f) {
  return el('div', { class: 'mortgage' },
    header(f),
    liquidity(f),
    seriesSection(f, 'repayments'),
    seriesSection(f, 'interest'),
    principal(f),
    idle(f),
    amortizer(f));
}

function header(f) {
  const state = f.loans.every((l) => l.state === 'closed') ? 'closed' : 'open';
  const bits = [`offset ${f.offset}`, `${f.loans.length} loan${f.loans.length === 1 ? '' : 's'}`];
  if (f.observedRatePct != null) bits.push(`observed rate ${f.observedRatePct}%`);
  return el('section', { class: 'headroom' },
    el('div', {}, el('b', {}, f.name), ' ', stateTag(state)),
    el('div', { class: 'muted' }, bits.join(' · ')),
    el('div', { class: 'hint muted' },
      'A declared facility (mortgages.yaml): the offset→loan link is a declaration, not an inference.'));
}

function stateTag(state) {
  return el('span', { class: 'tag ' + state }, state);
}

// ---- 1. liquidity -------------------------------------------------------------------------------

function liquidity(f) {
  const head = ['Liquidity', el('span', { class: 'muted' },
    ` · offset ${money(f.offsetBalance)}${f.offsetDate ? ` (${date(f.offsetDate)})` : ''}`)];
  const rows = [
    liqRow('offset', f.offset, f.offsetBalance, f.offsetDate),
    ...f.loans.map((l) => liqRow('loan', l.account, l.balance, l.balanceDate)),
  ];
  return fold('liquidity:' + f.name, true, head, [
    el('p', { class: 'hint muted' },
      'Each balance is as at its own statement date (the account\'s newest statement row). The '
      + 'offset and the loans are never netted into one headline figure.'),
    scroll(el('table', {}, el('thead', {}, el('tr', {},
      el('th', {}, 'Account'), el('th', {}, 'Role'), el('th', { class: 'amount' }, 'Balance'),
      el('th', {}, 'As at'))), el('tbody', {}, ...rows))),
  ]);
}

function liqRow(role, ref, balance, at) {
  return el('tr', {},
    el('td', {}, accountChip(ctx.refdata, ref)),
    el('td', { class: 'muted' }, role),
    el('td', { class: 'amount' }, balance === null || balance === undefined ? '\u2014' : money(balance)),
    el('td', { class: 'muted' }, at ? date(at) : '\u2014'));
}

// ---- 2 & 3. repayments and interest -------------------------------------------------------------

function seriesSection(f, kind) {
  const title = kind === 'repayments' ? 'Repayments' : 'Interest';
  const count = f.loans.reduce((n, l) => n + l[kind].count, 0);
  const total = f.loans.reduce((n, l) => n + l[kind].total, 0);
  const head = [title, el('span', { class: 'muted' }, ` · ${count} recorded · ${money(total)}`)];
  const body = [el('p', { class: 'hint muted' }, kind === 'repayments'
    ? 'The offset debits the declared repayment commitment claims, per loan.'
    : 'The loan-side charges the declared interest commitment claims, per loan. This is a charge '
      + 'history — a fact — not a rate, and no rate is inferred from it.')];
  for (const l of f.loans) body.push(seriesBlock(l, kind));
  return fold(kind + ':' + f.name, false, head, body);
}

function seriesBlock(l, kind) {
  const s = l[kind];
  const span = s.first ? `${date(s.first)} → ${date(s.last)}` : '';
  const head = el('h4', {}, loanName(l), ' ', el('span', { class: 'muted' },
    s.count ? `${s.count} · ${span} · ${money(s.total)}` : 'no recorded movements'));
  if (!s.count) {
    return el('div', { class: 'mode-section' }, head, el('p', { class: 'muted' }, 'No recorded movements.'));
  }
  const rows = [...s.rows].reverse(); // newest first for reading
  return el('div', { class: 'mode-section' }, head,
    scroll(el('table', {}, el('thead', {}, el('tr', {},
      el('th', {}, 'Date'), el('th', {}, 'Description'), el('th', {}, 'Direction'),
      el('th', { class: 'amount' }, 'Amount'))), el('tbody', {}, ...rows.map(activityRow)))));
}

function activityRow(a) {
  return el('tr', {},
    el('td', {}, date(a.date)),
    el('td', { class: 'desc' }, a.rawDescription || ''),
    el('td', {}, direction(a.amount)),
    el('td', { class: 'amount' }, money(Math.abs(a.amount || 0))));
}

function loanName(l) {
  return l.label || l.account;
}

// ---- 4. principal -------------------------------------------------------------------------------

function principal(f) {
  const head = ['Principal', el('span', { class: 'muted' }, ' · estimate from recorded movements')];
  const body = [el('p', { class: 'hint muted' },
    'Principal = repayments − recorded interest: an estimate from recorded movements, not an '
    + 'observed transaction. The balance change is closing − opening over the recorded statements. '
    + 'Any residual between them is shown, never folded into principal.')];
  const rows = f.loans.map((l) => el('tr', {},
    el('td', {}, loanName(l)),
    el('td', { class: 'amount' }, money(l.repayments.total)),
    el('td', { class: 'amount' }, money(l.interest.total)),
    el('td', { class: 'amount' }, el('span', { class: 'estimate' }, money(l.principal))),
    el('td', { class: 'amount' }, l.change === null ? '\u2014' : money(l.change)),
    el('td', { class: 'amount' }, residualCell(l)),
    el('td', { class: 'muted' }, l.reconciled ? '' : el('span', { class: 'occ-partial' }, 'not reconciled'))));
  body.push(scroll(el('table', {}, el('thead', {}, el('tr', {},
    el('th', {}, 'Loan'), el('th', { class: 'amount' }, 'Repayments'),
    el('th', { class: 'amount' }, 'Interest'),
    el('th', { class: 'amount' }, 'Principal\u00a0(est)'),
    el('th', { class: 'amount' }, 'Balance change'),
    el('th', { class: 'amount' }, 'Residual'), el('th', {}, ''))), el('tbody', {}, ...rows))));
  return fold('principal:' + f.name, true, head, body);
}

function residualCell(l) {
  if (l.residual === null) {
    return '\u2014';
  }
  if (l.residual === 0) {
    return el('span', { class: 'tick' }, money(0));
  }
  return el('span', { class: 'occ-partial', title: 'balance change − principal (an unexplained difference)' },
    money(l.residual));
}

// ---- 5. idle-offset insight ---------------------------------------------------------------------

function idle(f) {
  const head = ['Offset vs loans', el('span', { class: 'muted' },
    ` · working ${money(f.working)} · idle ${money(f.idle)}`)];
  const scope = f.sheltered
    ? `It shelters only ${f.sheltered.label || f.sheltered.account} (declared in mortgages.yaml) — `
      + `a balance of ${money(f.shelteredDebt)}.`
    : `It is declared against the whole facility (${money(f.debt)} of loans).`;
  return fold('idle:' + f.name, true, head, [
    el('div', {},
      `The offset holds ${money(f.offsetBalance)}. ${scope} `,
      el('b', {}, `${money(f.working)} working`), ', ',
      el('b', { class: f.idle ? 'occ-partial' : 'tick' }, `${money(f.idle)} idle`), '.'),
    costLine(f),
    el('p', { class: 'hint muted' },
      'Estimate only: it assumes the idle balance would offset the loan 1:1 and that the rate (or '
      + 'the last charge) holds. A contractual rate history, a payoff trajectory and property equity '
      + 'are not modelled.'),
  ]);
}

function costLine(f) {
  if (!f.idle) {
    return el('p', { class: 'muted' }, 'The whole offset is working, so no idle cost is estimated.');
  }
  if (f.observedRatePct != null) {
    const perYear = Math.round(f.idle * f.observedRatePct / 100);
    return el('p', {}, el('b', {}, `≈ ${money(perYear)} a year`),
      el('span', { class: 'muted' }, ` not saved (at the declared ${f.observedRatePct}%) — an estimate`));
  }
  const lastCharges = f.loans.reduce((n, l) => n + (l.interest.lastAmount || 0), 0);
  const lastDate = f.loans.map((l) => l.interest.last).filter(Boolean).sort().pop();
  if (f.debt > 0 && lastCharges > 0) {
    const perCharge = Math.round(f.idle * lastCharges / f.debt);
    return el('p', {}, el('b', {}, `≈ ${money(perCharge)} each charge period`),
      el('span', { class: 'muted' },
        ` not saved (at the latest recorded charge ${money(lastCharges)}`
        + (lastDate ? ` on ${date(lastDate)}` : '') + ') — an estimate'));
  }
  return el('p', { class: 'muted' },
    'No observed rate is declared and no interest charge is on record, so the idle offset\'s cost '
    + 'is not estimated.');
}

// ---- 6. the amortiser ---------------------------------------------------------------------------

// The inputs live here, keyed by facility name, so a re-render (an SSE delta rebuilds the view) does
// not wipe an edit. Seeded from the facility's current figures the first time it is seen; "Reset to
// current" clears the entry so the next render re-seeds.
const amort = new Map();

function seedAmort(f) {
  let s = amort.get(f.name);
  if (!s) {
    s = {
      loan: Math.round(f.debt || 0),
      offset: Math.round(Math.max(0, f.offsetBalance || 0)),
      rate: f.observedRatePct != null ? f.observedRatePct : 6,
      pay: monthlyRepayment(f),
      extra: 0,
    };
    amort.set(f.name, s);
  }
  return s;
}

/** The recent monthly repayment: the last 12 months of the facility's repayment commitments. */
function monthlyRepayment(f) {
  const rows = f.loans.flatMap((l) => l.repayments.rows || []);
  const cutoff = isoDaysAgo(365);
  const recent = rows.filter((r) => r.date >= cutoff);
  if (!recent.length) return 0;
  return Math.round(recent.reduce((n, r) => n + Math.abs(r.amount || 0), 0) / 12);
}

function isoDaysAgo(days) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

/**
 * A linked-offset amortisation: interest is the rate on the effective balance (loan − offset) while
 * the loan exceeds the offset; the monthly payment (plus any extra) pays that interest and reduces
 * the loan. It stops when the loan falls to the offset — the point the effective balance, and so the
 * interest, reach zero. Illustrative: it holds the rate, the payment and the offset flat, and models
 * no fees, redraws, splits or fixed-rate periods.
 */
function amortise(loan, offset, ratePct, pay, extra) {
  const r = ratePct / 100 / 12;
  const monthly = pay + extra;
  let bal = loan, cum = 0, months = 0;
  const trace = [];
  if (bal <= offset || monthly <= 0) {
    return { months: 0, cum: 0, trace, stalled: false };
  }
  const cap = 12 * 60;
  while (bal > offset + 0.01 && months < cap) {
    const interest = (bal - offset) * r;
    if (monthly <= interest) return { months: Infinity, cum, trace, stalled: true };
    bal -= (monthly - interest);
    cum += interest;
    months += 1;
    if (months % 12 === 0) {
      trace.push({ year: months / 12, loan: Math.max(0, bal), net: Math.max(0, bal - offset), cum });
    }
  }
  return { months, cum, trace, stalled: false };
}

function amortizer(f) {
  const s = seedAmort(f);
  const out = el('div', { class: 'amort-out' });
  const recompute = () => { clear(out); for (const node of amortResult(s)) out.append(node); };

  // Balances and payments are edited in dollars, held in cents.
  const dollars = (key, label, step) => el('label', { class: 'amort-field' }, label,
    el('input', { type: 'number', min: '0', step, value: String(Math.round(s[key] / 100)),
      oninput: (e) => { s[key] = toCents(parseNum(e.target.value)); recompute(); } }));

  recompute();
  return fold('amortiser:' + f.name, true,
    ['Amortiser', el('span', { class: 'muted' }, ' · defaults are the current figures; edit to explore')],
    [
      el('p', { class: 'hint muted' },
        'Interest is charged on (loan − offset); the payment covers it and reduces the loan; the '
        + 'projection ends when the loan falls to the offset and the interest reaches zero. It holds '
        + 'the rate, payment and offset flat — an illustration, not a forecast.'),
      el('div', { class: 'amort-controls' },
        dollars('loan', 'Loan', '1000'),
        dollars('offset', 'Offset', '1000'),
        el('label', { class: 'amort-field' }, 'Rate %',
          el('input', { type: 'number', min: '0', step: '0.05', value: String(s.rate),
            oninput: (e) => { s.rate = parseNum(e.target.value); recompute(); } })),
        dollars('pay', 'Repayment / mo', '50'),
        dollars('extra', 'Extra / mo', '50'),
        el('button', { type: 'button', class: 'ghost',
          onclick: () => { amort.delete(f.name); render(); } }, 'Reset to current')),
      out,
    ]);
}

function amortResult(s) {
  const r = s.rate / 100 / 12;
  const net = Math.max(0, s.loan - s.offset);
  const interest = Math.round(net * r);
  const principal = s.pay + s.extra - interest;
  const res = amortise(s.loan, s.offset, s.rate, s.pay, s.extra);
  const nodes = [el('div', { class: 'amort-summary' },
    stat('Effective balance', money(net)),
    stat('Interest / month', money(interest)),
    stat('Principal / month', money(principal)))];
  if (s.loan <= s.offset) {
    nodes.push(el('p', { class: 'muted' },
      'The offset already covers the loan: the effective balance, and the interest, are zero.'));
    return nodes;
  }
  if (res.stalled) {
    nodes.push(el('p', { class: 'occ-partial' },
      'The payment does not cover the interest, so the balance never falls.'));
    return nodes;
  }
  const y = Math.floor(res.months / 12), m = res.months % 12;
  nodes.push(el('p', {},
    el('b', {}, 'Debt-free (net) in '),
    `${y}y ${m}m · ~${res.months} payments · ${endMonth(res.months)}`,
    el('span', { class: 'muted' }, ` · total interest ${money(Math.round(res.cum))}`)));
  nodes.push(scroll(el('table', {}, el('thead', {}, el('tr', {},
    el('th', {}, 'Year'), el('th', { class: 'amount' }, 'Loan'),
    el('th', { class: 'amount' }, 'Effective'), el('th', { class: 'amount' }, 'Interest to date'))),
    el('tbody', {}, ...res.trace.map((t) => el('tr', {},
      el('td', {}, String(t.year)), el('td', { class: 'amount' }, money(Math.round(t.loan))),
      el('td', { class: 'amount' }, money(Math.round(t.net))),
      el('td', { class: 'amount' }, money(Math.round(t.cum)))))))));
  return nodes;
}

function endMonth(months) {
  const d = new Date();
  d.setMonth(d.getMonth() + months);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function stat(label, value) {
  return el('div', { class: 'amort-stat' },
    el('span', { class: 'muted' }, label), el('b', {}, value));
}

function parseNum(value) {
  const n = parseFloat(value);
  return Number.isFinite(n) ? n : 0;
}

function toCents(dollars) {
  return Math.round((dollars || 0) * 100);
}

// ---- helpers ------------------------------------------------------------------------------------

/** Foldable section: the summary carries the live figures, so a collapsed section still speaks. */
function fold(key, open, head, body) {
  return el('details', { class: 'mode-section x-section', open },
    el('summary', {}, ...head), el('div', { class: 'x-body' }, ...body));
}
